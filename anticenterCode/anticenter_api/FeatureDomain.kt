package com.example.anticenter.anticenter_api

/**
 * Public-facing feature domain enumeration exposed through the shared API.
 * This decouples external integrators from the internal SelectFeatures enum so that
 * internal refactors do not force external recompilation (as long as semantic codes remain).
 */
enum class FeatureDomain(val code: String) {
    CALL("callProtection"),
    MEETING("meetingProtection"),
    URL("urlProtection"),
    EMAIL("emailProtection");

    companion object {
        fun fromCode(code: String): FeatureDomain? = values().firstOrNull { it.code == code }
    }
}