package com.example.anticenter.anticenter_api

/**
 * Public AntiCenter database access contract.
 *
 * External integrators depend only on this interface + the lightweight [FeatureDomain] enum.
 * Internally the implementation will map [FeatureDomain] to the internal SelectFeatures enum.
 */
interface AntiCenterAPI {
    /**
     * Check whether a raw value (phone / email / URL, etc.) is present in the allow‑list
     * for the specified feature type.
     *
     * @param value The normalized value to check.
     * @param featureType The feature / protection domain scope.
     * @return true if present; false otherwise.
     */
    suspend fun isInAllowlist(value: String, featureType: FeatureDomain): Boolean

    /**
     * Report (persist) a detected threat / suspicious event.
     *
     * This is the primary write path invoked by detection modules once they classify
     * an incoming artifact (call, email, URL, etc.). Implementation will map this call
     * into an internal alert log record.
     *
     * @param threatType A short category label (e.g. "Phishing Email", "Suspicious Call").
     * @param source The original source identifier (phone number, email address, URL, etc.).
     * @param status Current status label (e.g. "detected", "suspicious", "blocked").
     * @param featureType The feature domain producing the report.
     * @param additionalInfo Optional extra context (key=value semantics left to caller).
     * @return [Result.success] if persisted; otherwise failed result with cause.
     */
    suspend fun reportThreat(
        threatType: String,
        source: String,
        status: String, // "detected", "suspicious" etc.
        featureType: FeatureDomain,
        additionalInfo: String = ""
    ): Result<Unit>

}