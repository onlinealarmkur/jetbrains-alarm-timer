package com.onlinealarmkur.jetbrains.domain

internal const val MAX_LABEL_LENGTH = 200

internal fun normalizeLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.length <= MAX_LABEL_LENGTH) return trimmed

    val cutoff = if (
        Character.isHighSurrogate(trimmed[MAX_LABEL_LENGTH - 1]) &&
        Character.isLowSurrogate(trimmed[MAX_LABEL_LENGTH])
    ) {
        MAX_LABEL_LENGTH - 1
    } else {
        MAX_LABEL_LENGTH
    }
    return trimmed.substring(0, cutoff)
}
