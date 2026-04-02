package com.example.bizhi.data

enum class LocalContentType {
    NONE,
    IMAGE,
    VIDEO,
    HTML;

    companion object {
        fun fromOrdinal(value: Int): LocalContentType =
            values().getOrElse(value) { NONE }
    }
}
