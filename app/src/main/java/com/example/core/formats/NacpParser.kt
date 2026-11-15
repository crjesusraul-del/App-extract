package com.example.core.formats

import com.example.core.io.BinaryUtils

data class NacpMetadata(
    val title: String,
    val publisher: String,
    val displayVersion: String
)

object NacpParser {
    private const val ENTRY_SIZE = 0x300
    private const val ENTRY_COUNT = 16

    fun parse(bytes: ByteArray): NacpMetadata {
        var bestTitle = ""
        var bestPublisher = ""

        // Prefer American English (0), British English (1), or first non-empty language
        val preferredOrder = listOf(0, 1) + (2 until ENTRY_COUNT)
        for (lang in preferredOrder) {
            val base = lang * ENTRY_SIZE
            if (base + ENTRY_SIZE > bytes.size) continue
            val name = BinaryUtils.readUtf8NullTerminated(bytes, base, 0x200)
            val pub = BinaryUtils.readUtf8NullTerminated(bytes, base + 0x200, 0x100)
            if (name.isNotEmpty()) {
                bestTitle = name
                bestPublisher = pub
                break
            }
        }

        val displayVersion = if (bytes.size >= 0x3070) {
            BinaryUtils.readUtf8NullTerminated(bytes, 0x3060, 0x10)
        } else {
            ""
        }

        return NacpMetadata(
            title = bestTitle.ifBlank { "Unknown Title" },
            publisher = bestPublisher.ifBlank { "Unknown Publisher" },
            displayVersion = displayVersion.ifBlank { "1.0.0" }
        )
    }
}
