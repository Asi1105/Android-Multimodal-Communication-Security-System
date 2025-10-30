package com.example.anticenter.collectors

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.anticenter.data.PhishingData
import com.example.anticenter.data.PhishingDataHub
import com.example.anticenter.services.ZoomCapService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * ✅ ZoomCollector - 收集 Zoom 音视频及截图检测结果并入 DataHub。
 * 由 ZoomCapService 在 RealityDefender 检测完成后回调。
 */
class ZoomCollector(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val dataHub: PhishingDataHub
) : Collector {

    companion object {
        private const val TAG = "ZoomCollector"

        /** 🔁 单例模式：允许 ZoomCapService 调用 processDetectionResult() */
        @Volatile private var instance: ZoomCollector? = null
        fun getInstance(): ZoomCollector? = instance
    }

    init {
        instance = this
        Log.i(TAG, "ZoomCollector initialized and registered as singleton.")
    }

    override val id: CollectorId = CollectorId.ZOOM
    private val _state = MutableStateFlow(CollectorState.IDLE)
    override val state: StateFlow<CollectorState> = _state
    private val scope = CoroutineScope(Dispatchers.IO)

    var projectionConsent: Intent? = null

    suspend fun startWithConsent(consent: Intent): Boolean {
        projectionConsent = consent
        return start()
    }

    override suspend fun start(): Boolean {
        if (_state.value == CollectorState.RUNNING || _state.value == CollectorState.STARTING) return true
        val consent = projectionConsent
        if (consent == null) {
            Log.e(TAG, "start() without projection consent")
            _state.value = CollectorState.ERROR
            return false
        }

        return try {
            _state.value = CollectorState.STARTING

            val it = Intent(context, ZoomCapService::class.java).apply {
                putExtra("consent", consent)
            }
            ContextCompat.startForegroundService(context, it)
            Log.i(TAG, "Requested to start ZoomCapService")

            _state.value = CollectorState.RUNNING
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Start Zoom failed", t)
            _state.value = CollectorState.ERROR
            false
        }
    }

    override suspend fun stop() {
        if (_state.value == CollectorState.IDLE || _state.value == CollectorState.STOPPING) return
        _state.value = CollectorState.STOPPING
        try {
            context.stopService(Intent(context, ZoomCapService::class.java))
            Log.i(TAG, "ZoomCapService stopped")
            _state.value = CollectorState.IDLE
        } catch (t: Throwable) {
            Log.e(TAG, "Stop Zoom failed", t)
            _state.value = CollectorState.ERROR
        }
    }

    override fun isRunning(): Boolean = _state.value == CollectorState.RUNNING

    // ======================================================
    // 🧠 被 ZoomCapService 调用，用于把检测结果推入 DataHub
    // 支持多种检测器：RealityDefender (RD) 和 Dify Voice Detector
    // ======================================================
    fun processDetectionResult(file: File, resultStatus: String, type: String) {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))

                // 根据类型判断检测器
                val detectorName = when (type) {
                    "AUDIO_PHISHING" -> "DifyVoiceDetector"
                    else -> "RealityDefender"
                }

                val metadata = mapOf(
                    "fileName" to file.name,
                    "filePath" to file.absolutePath,
                    "detector" to detectorName,
                    "resultStatus" to resultStatus,
                    "mediaType" to type,
                    "timestamp" to formatted
                )

                val phishingData = PhishingData(
                    dataType = "Zoom",
                    content = "Zoom ${type.lowercase()} analysis result: $resultStatus",
                    metadata = metadata
                )

                // 🚨 条件更新：MANIPULATED（deepfake）或 PHISHING 一律入 DataHub
                val statusUpper = resultStatus.uppercase(Locale.US)
                if (statusUpper == "PHISHING" || statusUpper == "MANIPULATED") {
                    PhishingDataHub.addData(phishingData)
                    Log.i(TAG, "📩 Added to DataHub: ${file.name} -> $resultStatus (${type.uppercase()}) by $detectorName")
                } else {
                    Log.i(TAG, "✅ ${file.name} clean ($resultStatus), not stored.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "processDetectionResult failed for ${file.name}", e)
            }
        }
    }
}
