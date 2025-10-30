package com.example.anticenter.anticenter_api

/**
 * Strongly typed allow‑list value category to replace raw String usage ("Phone", "Email", "URL").
 * Adding an enum removes risk of silent typos and enables compile‑time guidance.
 */
enum class AllowlistValueType(val wireName: String) {
    PHONE("Phone"),
    EMAIL("Email"),
    URL("URL");

    companion object {
        /** Resolve a raw persisted or user‑provided string into an enum (case sensitive on stored form). */
        fun fromWire(value: String): AllowlistValueType? = values().firstOrNull { it.wireName == value }
    }
}