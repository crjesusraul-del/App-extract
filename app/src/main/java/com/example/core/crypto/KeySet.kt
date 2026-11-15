package com.example.core.crypto

import com.example.core.io.BinaryUtils
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

data class KeyValidationSummary(
    val isLoaded: Boolean = false,
    val totalKeysCount: Int = 0,
    val hasHeaderKey: Boolean = false,
    val masterKeyCount: Int = 0,
    val keyAreaAppCount: Int = 0,
    val titleKekCount: Int = 0,
    val titleKeysCount: Int = 0,
    val warnings: List<String> = emptyList()
) {
    val isUsableForExtraction: Boolean
        get() = isLoaded && hasHeaderKey && (keyAreaAppCount > 0 || masterKeyCount > 0)
}

class KeySet(
    val headerKey: ByteArray? = null,
    val keyAreaKeyApplication: Map<Int, ByteArray> = emptyMap(),
    val titleKeks: Map<Int, ByteArray> = emptyMap(),
    val masterKeys: Map<Int, ByteArray> = emptyMap(),
    val titleKeys: MutableMap<String, ByteArray> = mutableMapOf(),
    val encryptedTitleKeys: MutableMap<String, ByteArray> = mutableMapOf(),
    val rawKeyCount: Int = 0
) {
    fun getKeyAreaKeyApp(generation: Int): ByteArray? = keyAreaKeyApplication[generation]

    fun getTitleKek(generation: Int): ByteArray? = titleKeks[generation]

    fun getPlainTitleKey(rightsIdHex: String): ByteArray? =
        titleKeys[rightsIdHex.lowercase(Locale.US)]

    fun getEncryptedTitleKey(rightsIdHex: String): ByteArray? =
        encryptedTitleKeys[rightsIdHex.lowercase(Locale.US)]

    fun putPlainTitleKey(rightsIdHex: String, plainKey: ByteArray) {
        titleKeys[rightsIdHex.lowercase(Locale.US)] = plainKey
    }

    fun putEncryptedTitleKey(rightsIdHex: String, encryptedKey: ByteArray) {
        encryptedTitleKeys[rightsIdHex.lowercase(Locale.US)] = encryptedKey
    }

    fun summarize(): KeyValidationSummary {
        val warnings = mutableListOf<String>()
        if (headerKey == null || headerKey.size != 32) {
            warnings += "Missing or invalid header_key (must be 32 bytes / 64 hex chars)."
        }
        if (keyAreaKeyApplication.isEmpty()) {
            warnings += "No key_area_key_application_XX entries found."
        }
        if (masterKeys.isEmpty()) {
            warnings += "No master_key_XX entries found."
        }
        return KeyValidationSummary(
            isLoaded = rawKeyCount > 0,
            totalKeysCount = rawKeyCount,
            hasHeaderKey = headerKey != null && headerKey.size == 32,
            masterKeyCount = masterKeys.size,
            keyAreaAppCount = keyAreaKeyApplication.size,
            titleKekCount = titleKeks.size,
            titleKeysCount = titleKeys.size,
            warnings = warnings
        )
    }

    companion object {
        fun parse(prodKeysStream: InputStream, titleKeysStream: InputStream? = null): KeySet {
            var headerKey: ByteArray? = null
            val keyAreaApp = mutableMapOf<Int, ByteArray>()
            val titleKeks = mutableMapOf<Int, ByteArray>()
            val masterKeys = mutableMapOf<Int, ByteArray>()
            val titleKeys = mutableMapOf<String, ByteArray>()
            var count = 0

            val reader = BufferedReader(InputStreamReader(prodKeysStream, Charsets.UTF_8))
            reader.forEachLine { rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty() || !line.contains('=')) return@forEachLine
                val idx = line.indexOf('=')
                val keyName = line.substring(0, idx).trim().lowercase(Locale.US)
                val keyHex = line.substring(idx + 1).trim()
                if (keyHex.isEmpty() || keyHex.length % 2 != 0) return@forEachLine
                val keyBytes = runCatching { BinaryUtils.hexToBytes(keyHex) }.getOrNull()
                    ?: return@forEachLine
                count++

                when {
                    keyName == "header_key" && keyBytes.size == 32 -> {
                        headerKey = keyBytes
                    }
                    keyName.startsWith("key_area_key_application_") && keyBytes.size == 16 -> {
                        keyName.removePrefix("key_area_key_application_").toIntOrNull(16)?.let { gen ->
                            keyAreaApp[gen] = keyBytes
                        }
                    }
                    keyName.startsWith("titlekek_") && keyBytes.size == 16 -> {
                        keyName.removePrefix("titlekek_").toIntOrNull(16)?.let { gen ->
                            titleKeks[gen] = keyBytes
                        }
                    }
                    keyName.startsWith("master_key_") && keyBytes.size == 16 -> {
                        keyName.removePrefix("master_key_").toIntOrNull(16)?.let { gen ->
                            masterKeys[gen] = keyBytes
                        }
                    }
                }
            }

            if (titleKeysStream != null) {
                val tReader = BufferedReader(InputStreamReader(titleKeysStream, Charsets.UTF_8))
                tReader.forEachLine { rawLine ->
                    val line = rawLine.substringBefore('#').trim()
                    if (line.isEmpty() || !line.contains('=')) return@forEachLine
                    val idx = line.indexOf('=')
                    val rightsId = line.substring(0, idx).trim().lowercase(Locale.US)
                    val keyHex = line.substring(idx + 1).trim()
                    if (rightsId.length == 32 && keyHex.length == 32) {
                        runCatching { BinaryUtils.hexToBytes(keyHex) }.getOrNull()?.let { bytes ->
                            titleKeys[rightsId] = bytes
                        }
                    }
                }
            }

            return KeySet(
                headerKey = headerKey,
                keyAreaKeyApplication = keyAreaApp,
                titleKeks = titleKeks,
                masterKeys = masterKeys,
                titleKeys = titleKeys,
                rawKeyCount = count
            )
        }
    }
}
