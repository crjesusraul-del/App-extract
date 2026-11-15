package com.example.data

import com.squareup.moshi.JsonClass

enum class DumpFormat {
    XCI,
    NSP,
    NCA
}

enum class ExtractionTarget(val label: String, val description: String) {
    EXEFS("ExeFS", "Executable code, main binary & NPDM"),
    ROMFS("RomFS", "Game assets, textures, audio & scripts"),
    BOTH("ExeFS + RomFS", "Full unpacked game filesystem")
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light")
}

@JsonClass(generateAdapter = true)
data class AttachedUpdate(
    val uriString: String,
    val fileName: String,
    val format: DumpFormat,
    val titleIdHex: String,
    val displayVersion: String,
    val versionCode: Long,
    val fileSizeBytes: Long,
    val hasBktr: Boolean,
    val isLooseFile: Boolean = false
)

@JsonClass(generateAdapter = true)
data class IndexedGame(
    val id: String,
    val uriString: String,
    val fileName: String,
    val format: DumpFormat,
    val titleIdHex: String,
    val titleName: String,
    val publisher: String,
    val displayVersion: String,
    val versionCode: Long,
    val fileSizeBytes: Long,
    val hasExeFs: Boolean,
    val hasRomFs: Boolean,
    val exeFsSizeBytes: Long,
    val romFsSizeBytes: Long,
    val iconCachePath: String? = null,
    val ncaSummary: List<String> = emptyList(),
    val matchedUpdates: List<AttachedUpdate> = emptyList(),
    val isLooseFile: Boolean = false,
    val isUpdateOnlyWithoutBase: Boolean = false
)

@JsonClass(generateAdapter = true)
data class UnsupportedFileEntry(
    val fileName: String,
    val uriString: String,
    val reason: String,
    val fileSizeBytes: Long
)

@JsonClass(generateAdapter = true)
data class CachedLibraryPayload(
    val games: List<IndexedGame> = emptyList(),
    val unsupported: List<UnsupportedFileEntry> = emptyList(),
    val lastScannedEpochMs: Long = 0L
)

data class SetupConfig(
    val prodKeysUri: String? = null,
    val prodKeysName: String? = null,
    val titleKeysUri: String? = null,
    val titleKeysName: String? = null,
    val gameDirUri: String? = null,
    val gameDirName: String? = null,
    val outputDirUri: String? = null,
    val outputDirName: String? = null,
    val outputDirRestrictedWarning: String? = null,
    val looseFileUris: List<String> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false
) {
    val isMinimumSetupComplete: Boolean
        get() = !prodKeysUri.isNullOrBlank() && !gameDirUri.isNullOrBlank() && !outputDirUri.isNullOrBlank() && outputDirRestrictedWarning == null
}

enum class ExtractionStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    CANCELLED,
    FAILED
}

data class ExtractionRequest(
    val game: IndexedGame,
    val target: ExtractionTarget,
    val selectedUpdate: AttachedUpdate?,
    val mergeUpdate: Boolean
)

data class ExtractionProgress(
    val jobId: String,
    val gameTitle: String,
    val titleIdHex: String,
    val target: ExtractionTarget,
    val mergedUpdateVersion: String?,
    val status: ExtractionStatus,
    val phaseLabel: String,
    val currentFile: String,
    val filesDone: Int,
    val totalFiles: Int,
    val bytesDone: Long,
    val totalBytes: Long,
    val outputFolderLabel: String,
    val errorMessage: String? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long? = null
) {
    val fraction: Float
        get() = when {
            status == ExtractionStatus.COMPLETED -> 1f
            totalBytes > 0L -> (bytesDone.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalFiles > 0 -> (filesDone.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}
