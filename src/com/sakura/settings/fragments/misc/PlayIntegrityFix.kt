/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sakura.settings.fragments.misc

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PlayIntegrityFix : SettingsPreferenceFragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeConfigFileName: String? = null
    private var activeConfigData: Map<String, String> = emptyMap()

    private enum class PifChannel { LATEST_RELEASE, CANARY }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val pifDir = File(PIF_PATH).also { if (!it.exists()) it.mkdirs() }
                    val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "pif.prop"
                    val targetName = if (fileName.endsWith(".json")) "custom.pif.json" else "custom.pif.prop"
                    val targetFile = File(pifDir, targetName)

                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    targetFile.setReadable(true, false)
                    killPlayStore()
                    toast(getString(R.string.pif_imported_as, targetName))
                    refreshStatus()
                } catch (e: Exception) {
                    toast(getString(R.string.pif_failed, e.message ?: ""))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.play_integrity_fix)

        findPreference<Preference>("pif_fetch_beta")?.setOnPreferenceClickListener {
            showChannelSelectionDialog()
            true
        }

        findPreference<Preference>("pif_import_config")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            importLauncher.launch(intent)
            true
        }

        findPreference<Preference>("pif_delete_config")?.setOnPreferenceClickListener {
            showDeleteDialog()
            true
        }

        findPreference<SwitchPreferenceCompat>("pif_spoof_photos")?.setOnPreferenceChangeListener { _, newValue ->
            updateConfigValue("spoofPhotos", (newValue as Boolean).toString())
            true
        }

        File(PIF_PATH).also { if (!it.exists()) it.mkdirs() }
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun refreshStatus() {
        var foundActive: String? = null
        var activeData: Map<String, String> = emptyMap()

        for (fileName in PIF_FILES) {
            val file = File(PIF_PATH, fileName)
            if (file.exists()) {
                if (foundActive == null) {
                    foundActive = fileName
                    activeData = readConfigData(file)
                }
            }
        }

        activeConfigFileName = foundActive
        activeConfigData = activeData

        // Update active config preference
        val activePref = findPreference<Preference>("pif_active_config")
        if (foundActive != null) {
            val model = activeData["MODEL"] ?: ""
            val fingerprint = activeData["FINGERPRINT"] ?: ""
            activePref?.title = foundActive
            activePref?.summary = if (model.isNotEmpty()) {
                "MODEL: $model" + if (fingerprint.isNotEmpty()) "\nFINGERPRINT: $fingerprint" else ""
            } else {
                getString(R.string.pif_config_loaded)
            }
        } else {
            activePref?.title = getString(R.string.pif_active_config)
            activePref?.summary = getString(R.string.pif_no_config)
        }

        // Update delete button
        findPreference<Preference>("pif_delete_config")?.isEnabled = foundActive != null

        // Update spoofPhotos switch
        val spoofPhotos = activeData["spoofPhotos"]?.let { it == "true" || it == "1" } ?: false
        findPreference<SwitchPreferenceCompat>("pif_spoof_photos")?.isChecked = spoofPhotos

        // Populate config details
        populateConfigDetails(activeData)
    }

    private fun populateConfigDetails(data: Map<String, String>) {
        val category = findPreference<PreferenceCategory>("pif_config_details_category") ?: return
        category.removeAll()

        if (data.isEmpty()) return

        val displayOrder = listOf(
            "MODEL", "MANUFACTURER", "BRAND", "PRODUCT", "DEVICE",
            "FINGERPRINT", "SECURITY_PATCH", "ID", "RELEASE", "DEVICE_INITIAL_SDK_INT"
        )

        for (key in displayOrder) {
            val value = data[key] ?: continue
            category.addPreference(Preference(requireContext()).apply {
                this.title = key
                this.summary = value
                isSelectable = false
            })
        }

        // Show remaining keys not in displayOrder
        data.keys.filter { it !in displayOrder && !it.startsWith("spoof") && it != "DEBUG" && it != "verboseLogs" }
            .forEach { key ->
                category.addPreference(Preference(requireContext()).apply {
                    this.title = key
                    this.summary = data[key]
                    isSelectable = false
                })
            }
    }

    private fun showDeleteDialog() {
        val fileName = activeConfigFileName ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pif_delete_title, fileName))
            .setMessage(R.string.pif_delete_message)
            .setPositiveButton(R.string.pif_delete) { _, _ ->
                try {
                    File(PIF_PATH, fileName).delete()
                    toast(getString(R.string.pif_deleted, fileName))
                    refreshStatus()
                } catch (e: Exception) {
                    toast(getString(R.string.pif_failed, e.message ?: ""))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showChannelSelectionDialog() {
        val channels = arrayOf(
            getString(R.string.pif_channel_latest_release),
            getString(R.string.pif_channel_canary_release)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.pif_select_channel)
            .setItems(channels) { _, which ->
                val channel = if (which == 0) PifChannel.LATEST_RELEASE else PifChannel.CANARY
                fetchDevicesForChannel(channel)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetchDevicesForChannel(channel: PifChannel) {
        val fetchPref = findPreference<Preference>("pif_fetch_beta") ?: return
        fetchPref.summary = getString(R.string.pif_fetching)
        fetchPref.isEnabled = false

        scope.launch {
            try {
                val (devices, apiKey) = withContext(Dispatchers.IO) {
                    when (channel) {
                        PifChannel.LATEST_RELEASE -> fetchAvailableDevices() to null
                        PifChannel.CANARY -> fetchAvailableCanaryDevices()
                    }
                }

                if (devices.isEmpty()) {
                    toast(getString(R.string.pif_failed, "No devices found"))
                    return@launch
                }

                if (channel == PifChannel.CANARY && apiKey.isNullOrEmpty()) {
                    toast(getString(R.string.pif_failed, "Failed to extract Flash Tool API key"))
                    return@launch
                }

                // Resolve model names and show picker
                val modelNames = devices.map { it.model }.toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pif_select_device)
                    .setItems(modelNames) { _, which ->
                        generateAndSavePif(devices[which], channel, apiKey)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                toast(getString(R.string.pif_failed, e.message ?: ""))
            } finally {
                fetchPref.summary = getString(R.string.pif_fetch_pixel_beta_summary)
                fetchPref.isEnabled = true
            }
        }
    }

    private fun generateAndSavePif(device: PifDevice, channel: PifChannel, apiKey: String?) {
        val fetchPref = findPreference<Preference>("pif_fetch_beta")
        fetchPref?.summary = getString(R.string.pif_generating)
        fetchPref?.isEnabled = false

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (channel) {
                        PifChannel.LATEST_RELEASE -> buildPifFromDevice(device)
                        PifChannel.CANARY -> buildCanaryPifFromDevice(device, apiKey ?: "")
                    }
                }
                when (result) {
                    is PifFetchResult.Success -> {
                        withContext(Dispatchers.IO) {
                            val pifDir = File(PIF_PATH).also { if (!it.exists()) it.mkdirs() }
                            val targetFile = File(pifDir, "pif.json")
                            targetFile.writeText(result.pifData.toString(2))
                            targetFile.setReadable(true, false)
                        }
                        killPlayStore()
                        toast(getString(R.string.pif_fetched_model, result.model))
                        refreshStatus()
                    }
                    is PifFetchResult.Error -> {
                        toast(getString(R.string.pif_failed, result.message))
                    }
                }
            } catch (e: Exception) {
                toast(getString(R.string.pif_failed, e.message ?: ""))
            } finally {
                fetchPref?.summary = getString(R.string.pif_fetch_pixel_beta_summary)
                fetchPref?.isEnabled = true
            }
        }
    }

    /**
     * Updates a key-value pair in the active config file.
     * If no config file exists, creates pif.json with just this value.
     */
    private fun updateConfigValue(key: String, value: String) {
        val fileName = activeConfigFileName ?: DEFAULT_CONFIG_FILE
        try {
            val pifDir = File(PIF_PATH).also { if (!it.exists()) it.mkdirs() }
            val file = File(pifDir, fileName)
            if (fileName.endsWith(".json")) {
                val json = if (file.exists()) JSONObject(file.readText()) else JSONObject()
                json.put(key, value)
                file.writeText(json.toString(2))
            } else {
                val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
                val idx = lines.indexOfFirst { it.trim().startsWith("$key=") }
                if (idx != -1) lines[idx] = "$key=$value" else lines.add("$key=$value")
                file.writeText(lines.joinToString("\n"))
            }
            file.setReadable(true, false)
            refreshStatus()
        } catch (e: Exception) {
            toast(getString(R.string.pif_failed, e.message ?: ""))
        }
    }

    private fun killPlayStore() {
        try {
            val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.forceStopPackage(VENDING_PACKAGE)
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.SAKURA_SETTINGS

    companion object {
        private const val TAG = "PlayIntegrityFix"
        private const val PIF_PATH = "/data/adb/playintegrityfix"
        private val PIF_FILES = listOf("custom.pif.prop", "custom.pif.json", "pif.prop", "pif.json")
        private const val DEFAULT_CONFIG_FILE = "pif.json"
        private const val GOOGLE_URL = "https://developer.android.com"
        private const val FLASH_URL = "https://flash.android.com"
        private const val FLASH_API = "https://content-flashstation-pa.googleapis.com/v1/builds"
        private const val PIXEL_BULLETIN_URL = "https://source.android.com/docs/security/bulletin/pixel"
        private const val VENDING_PACKAGE = "com.android.vending"

        private val DEVICE_MODEL_MAP = mapOf(
            "oriole" to "Pixel 6",
            "raven" to "Pixel 6 Pro",
            "bluejay" to "Pixel 6a",
            "panther" to "Pixel 7",
            "cheetah" to "Pixel 7 Pro",
            "lynx" to "Pixel 7a",
            "shiba" to "Pixel 8",
            "tangorpro" to "Pixel Tablet",
            "felix" to "Pixel Fold",
            "husky" to "Pixel 8 Pro",
            "akita" to "Pixel 8a",
            "tokay" to "Pixel 9",
            "caiman" to "Pixel 9 Pro",
            "komodo" to "Pixel 9 Pro XL",
            "comet" to "Pixel 9 Pro Fold",
            "tegu" to "Pixel 9a",
            "frankel" to "Pixel 10",
            "blazer" to "Pixel 10 Pro",
            "mustang" to "Pixel 10 Pro XL",
            "rango" to "Pixel 10 Pro Fold",
            "stallion" to "Pixel 10a",
        )

        private fun readConfigData(file: File): Map<String, String> {
            if (!file.exists()) return emptyMap()
            return try {
                val content = file.readText()
                val result = mutableMapOf<String, String>()
                if (file.name.endsWith(".json")) {
                    val json = JSONObject(content)
                    json.keys().forEach { key -> result[key] = json.optString(key, "") }
                } else {
                    content.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("//")) {
                            val eq = trimmed.indexOf('=')
                            if (eq > 0) result[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                        }
                    }
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read config", e)
                emptyMap()
            }
        }

        private fun fetchPartialUrl(url: String, maxBytes: Int): String {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.getInputStream().use { input ->
                val buf = ByteArray(512)
                val sb = StringBuilder()
                var total = 0
                while (total < maxBytes) {
                    val read = input.read(buf)
                    if (read == -1) break
                    sb.append(String(buf, 0, read, StandardCharsets.ISO_8859_1))
                    total += read
                }
                return sb.toString()
            }
        }

        data class PifDevice(
            val product: String,
            val device: String,
            val model: String,
            val otaUrl: String,
        )

        private sealed class PifFetchResult {
            data class Success(val model: String, val pifData: JSONObject) : PifFetchResult()
            data class Error(val message: String) : PifFetchResult()
        }

        /**
         * Phase 1: Scrape Google developer site to find all available beta devices.
         */
        private fun fetchAvailableDevices(): List<PifDevice> {
            val versionsHtml = URL("$GOOGLE_URL/about/versions").readText(StandardCharsets.UTF_8)
            val knownVersions = Regex("""https://developer\.android\.com/about/versions/(\d+)""")
                .findAll(versionsHtml).map { it.groupValues[1].toInt() }.toSet().sortedDescending()

            // Try next unreleased version first (may have beta), then known versions
            val maxVersion = knownVersions.firstOrNull() ?: return emptyList()
            val versions = listOf(maxVersion + 1) + knownVersions

            for (version in versions) {
                try {
                    val downloadUrl = "$GOOGLE_URL/about/versions/$version/download-ota"
                    val otaHtml = URL(downloadUrl).readText(StandardCharsets.UTF_8)
                    val otaList = Regex("""href="(https://dl\.google\.com/[^"]*ota/([^/"]+_beta)[^"]*?)"""")
                        .findAll(otaHtml).map { it.groupValues[1] to it.groupValues[2] }.toList()
                    if (otaList.isEmpty()) continue

                    val devices = mutableListOf<PifDevice>()
                    val seen = mutableSetOf<String>()
                    for ((otaUrl, product) in otaList) {
                        val device = product.replace("_beta", "")
                        if (device in seen) continue
                        seen.add(device)
                        val model = DEVICE_MODEL_MAP[device] ?: device
                        devices.add(PifDevice(product, device, model, otaUrl))
                    }
                    if (devices.isNotEmpty()) return devices
                } catch (_: Exception) { continue }
            }
            return emptyList()
        }

        /**
         * Phase 2: Fetch OTA metadata for a specific device and build pif.json.
         */
        private fun buildPifFromDevice(pifDevice: PifDevice): PifFetchResult {
            try {
                val partial = fetchPartialUrl(pifDevice.otaUrl, 4096)

                val fingerprint = Regex("""post-build=(.*)""").find(partial)?.groupValues?.get(1)?.trim()
                    ?: return PifFetchResult.Error("Could not extract fingerprint")
                val securityPatch = Regex("""security-patch-level=(.*)""").find(partial)?.groupValues?.get(1)?.trim()
                    ?: return PifFetchResult.Error("Could not extract security patch")

                val fpParts = fingerprint.split("/")
                val release = fpParts.getOrNull(2)?.substringAfter(":", "") ?: ""
                val buildId = fpParts.getOrNull(3) ?: ""

                val pifJson = JSONObject().apply {
                    put("TYPE", "user")
                    put("TAGS", "release-keys")
                    put("ID", buildId)
                    put("BRAND", "google")
                    put("DEVICE", pifDevice.device)
                    put("FINGERPRINT", fingerprint)
                    put("MANUFACTURER", "Google")
                    put("MODEL", pifDevice.model)
                    put("PRODUCT", pifDevice.product)
                    put("RELEASE", release)
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "21")
                    put("DEBUG", false)
                    put("SDK_INT", "32")
                }
                return PifFetchResult.Success(pifDevice.model, pifJson)
            } catch (e: Exception) {
                return PifFetchResult.Error("Failed: ${e.message}")
            }
        }

        private fun fetchAvailableCanaryDevices(): Pair<List<PifDevice>, String?> {
            try {
                val versionsHtml = URL("$GOOGLE_URL/about/versions").readText(StandardCharsets.UTF_8)
                val latestVersion = Regex("""https://developer\.android\.com/about/versions/(\d+)""")
                    .findAll(versionsHtml)
                    .map { it.groupValues[1].toInt() }
                    .toSortedSet()
                    .maxOrNull() ?: return emptyList<PifDevice>() to null

                val latestHtml = URL("$GOOGLE_URL/about/versions/$latestVersion").readText(StandardCharsets.UTF_8)
                val qprPath = Regex("""href="(/about/versions/$latestVersion/qpr(\d+)/download-ota)"""")
                    .findAll(latestHtml)
                    .map { it.groupValues[2].toInt() to it.groupValues[1] }
                    .maxByOrNull { it.first }
                    ?.second ?: return emptyList<PifDevice>() to null

                val fiHtml = URL("$GOOGLE_URL$qprPath").readText(StandardCharsets.UTF_8)
                val rowPattern = Regex(
                    """<tr id="([^"]+)">\s*<td[^>]*>([^<]+)</td>""",
                    RegexOption.DOT_MATCHES_ALL
                )

                val devices = mutableListOf<PifDevice>()
                val seen = mutableSetOf<String>()
                rowPattern.findAll(fiHtml).forEach { match ->
                    val device = match.groupValues[1]
                    if (device in seen) return@forEach
                    seen.add(device)
                    val model = match.groupValues[2].trim().ifEmpty { DEVICE_MODEL_MAP[device] ?: device }
                    devices.add(
                        PifDevice(
                            product = "${device}_beta",
                            device = device,
                            model = model,
                            otaUrl = "",
                        )
                    )
                }

                if (devices.isEmpty()) return emptyList<PifDevice>() to null

                val flashHtml = URL(FLASH_URL).readText(StandardCharsets.UTF_8)
                val apiKey = Regex("""AIza[0-9A-Za-z_-]{35}""").find(flashHtml)?.value

                return devices to apiKey
            } catch (e: Exception) {
                Log.e(TAG, "Canary device fetch failed", e)
                return emptyList<PifDevice>() to null
            }
        }

        private fun buildCanaryPifFromDevice(pifDevice: PifDevice, apiKey: String): PifFetchResult {
            try {
                if (apiKey.isEmpty()) return PifFetchResult.Error("Flash Tool API key unavailable")

                val buildsUrl = "$FLASH_API?product=${pifDevice.product}&key=$apiKey"
                val buildsConn = URL(buildsUrl).openConnection().apply {
                    setRequestProperty("Referer", FLASH_URL)
                    setRequestProperty("X-Goog-Api-Key", apiKey)
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val buildsJson = buildsConn.getInputStream().use {
                    it.readBytes().toString(StandardCharsets.UTF_8)
                }

                val root = JSONObject(buildsJson)
                val buildsArray = root.optJSONArray("flashstationBuild")
                    ?: return PifFetchResult.Error("No flashstationBuild array in Flash Tool response")

                var id: String? = null
                var incremental: String? = null
                var canaryId: String? = null

                for (i in buildsArray.length() - 1 downTo 0) {
                    val b = buildsArray.optJSONObject(i) ?: continue
                    val meta = b.optJSONObject("previewMetadata") ?: continue
                    if (!meta.optBoolean("canary")) continue

                    val rc = b.optString("releaseCandidateName")
                    val bid = b.optString("buildId")
                    if (rc.isEmpty() || bid.isEmpty()) continue

                    id = rc
                    incremental = bid
                    canaryId = meta.optString("id").takeIf { it.contains("canary-") }
                    break
                }

                if (id == null || incremental == null) {
                    return PifFetchResult.Error("No canary build found for ${pifDevice.product}")
                }

                val fingerprint =
                    "google/${pifDevice.product}/${pifDevice.device}:CANARY/$id/$incremental:user/release-keys"

                val canaryMonth = canaryId?.let {
                    Regex("""canary-(\d{4})(\d{2})""").find(it)?.let { m ->
                        "${m.groupValues[1]}-${m.groupValues[2]}"
                    }
                } ?: return PifFetchResult.Error("Failed to derive canary month id")

                val securityPatch = try {
                    val bulletinHtml = URL(PIXEL_BULLETIN_URL).readText(StandardCharsets.UTF_8)
                    Regex("""<td>($canaryMonth-\d{2})</td>""").find(bulletinHtml)?.groupValues?.get(1)
                        ?: "$canaryMonth-05"
                } catch (e: Exception) {
                    Log.d(TAG, "Bulletin fetch failed, using estimated patch: ${e.message}")
                    "$canaryMonth-05"
                }

                val pifJson = JSONObject().apply {
                    put("TYPE", "user")
                    put("TAGS", "release-keys")
                    put("ID", id)
                    put("BRAND", "google")
                    put("DEVICE", pifDevice.device)
                    put("FINGERPRINT", fingerprint)
                    put("MANUFACTURER", "Google")
                    put("MODEL", pifDevice.model)
                    put("PRODUCT", pifDevice.product)
                    put("RELEASE", "CANARY")
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "32")
                    put("DEBUG", false)
                    put("SDK_INT", "32")
                }

                return PifFetchResult.Success(pifDevice.model, pifJson)
            } catch (e: Exception) {
                return PifFetchResult.Error("Failed: ${e.message}")
            }
        }
    }
}
