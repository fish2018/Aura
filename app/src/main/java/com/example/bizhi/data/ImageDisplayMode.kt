package com.example.bizhi.data

enum class ImageDisplayMode {
    CONTAIN,
    COVER,
    STRETCH;

    companion object {
        fun fromName(value: String?): ImageDisplayMode =
            entries.firstOrNull { it.name == value } ?: COVER
    }
}
