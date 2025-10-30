package com.example.anticenter.anticenter_api

import android.content.Context
import com.example.anticenter.SelectFeatures
import com.example.anticenter.data.AllowlistItem
import com.example.anticenter.data.AlertLogItem
import com.example.anticenter.database.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Concrete production implementation of [AntiCenterAPI].
 *
 * Delegates all persistence operations to the singleton [DatabaseManager], providing a
 * stable boundary so feature modules do not couple to raw database APIs.
 *
 * Concurrency & threading:
 * - All public suspend functions switch to [Dispatchers.IO] to keep heavy I/O off the main thread.
 * - The underlying [DatabaseManager] relies on the standard Android SQLiteOpenHelper which
 *   serializes writes; caller does not need extra synchronization.
 *
 * Error handling:
 * - Write operations return [Result] so callers can branch on success/failure explicitly.
 * - Read operations throw on unrecoverable failures (propagated via coroutine). Callers may wrap.
 *
 * ID generation strategy:
 * - Allow‑list entries use a millisecond timestamp string. This is simple, sortable, and
 *   sufficiently unique for typical mobile app scale. Replace with UUID if collision risk grows.
 */
class AntiCenterAPIImpl private constructor(
    private val databaseManager: DatabaseManager
) : AntiCenterAPI {
    companion object {
        @Volatile
        private var INSTANCE: AntiCenterAPIImpl? = null

        /**
         * Get or create the singleton implementation bound to the application context.
         */
        fun getInstance(context: Context): AntiCenterAPIImpl {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AntiCenterAPIImpl(
                    DatabaseManager.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }

    /** See [AntiCenterAPI.isInAllowlist]. */
    override suspend fun isInAllowlist(value: String, featureType: FeatureDomain): Boolean =
        withContext(Dispatchers.IO) {
            databaseManager.isValueInAllowlist(value, featureType.toInternal())
        }

    /** See [AntiCenterAPI.addToAllowlist]. */
    override suspend fun addToAllowlist(
        name: String,
        value: String,
        type: String,
        featureType: FeatureDomain,
        description: String
    ): Result<Unit> = addToAllowlist(
        name,
        value,
        AllowlistValueType.fromWire(type)
            ?: return Result.failure(IllegalArgumentException("Unsupported allowlist type: $type")),
        featureType,
        description
    )

    /** See [AntiCenterAPI.addToAllowlist]. */
    override suspend fun addToAllowlist(
        name: String,
        value: String,
        valueType: AllowlistValueType,
        featureType: FeatureDomain,
        description: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val item = AllowlistItem(
                id = System.currentTimeMillis().toString(),
                name = name,
                type = valueType.wireName,
                value = value,
                description = description
            )
            val result = databaseManager.insertAllowlistItem(item, featureType.toInternal())
            if (result > 0) Result.success(Unit) else Result.failure(Exception("Insert failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** See [AntiCenterAPI.reportThreat]. */
    override suspend fun reportThreat(
        threatType: String,
        source: String,
        status: String,
        featureType: FeatureDomain,
        additionalInfo: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val alertItem = AlertLogItem(
                time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                type = threatType,
                source = source,
                status = status
            )
            val result = databaseManager.insertAlertLogItem(alertItem, featureType.toInternal())
            if (result > 0) Result.success(Unit) else Result.failure(Exception("Insert failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Map public facing [FeatureDomain] to internal [SelectFeatures]. */
    private fun FeatureDomain.toInternal(): SelectFeatures = when (this) {
        FeatureDomain.CALL -> SelectFeatures.callProtection
        FeatureDomain.MEETING -> SelectFeatures.meetingProtection
        FeatureDomain.URL -> SelectFeatures.urlProtection
        FeatureDomain.EMAIL -> SelectFeatures.emailProtection
    }
}
