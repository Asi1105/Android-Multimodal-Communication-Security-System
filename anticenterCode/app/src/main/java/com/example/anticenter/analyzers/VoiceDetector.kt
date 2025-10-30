package com.example.anticenter.analyzers

import android.util.Log
import com.example.anticenter.data.PhishingData
import com.example.anticenter.data.PhishingDataHub
import com.example.anticenter.analyzers.FileTestResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
/**
 * VoiceDetector：
 * 直接监听手机录音模块（IntegratedFileUploadManager）的检测结果，
 * 当检测到 phishing 时，将警报信息推送回 PhishingDataHub。
 */
class VoiceDetector {

    companion object {
        private const val TAG = "VoiceDetector"
    }

    /**
     * 接收来自录音模块（FileUploadManager）的检测结果
     */
    fun onVoiceDetectionResult(result: FileTestResult) {
        Log.d(TAG, "Received voice detection result for ${result.fileName}: ${result.llmDecision}")

        if (result.llmDecision.equals("PHISHING", ignoreCase = true)) {
            val alert = PhishingData(
                dataType = "Voice",
                content = "⚠️ Detected phishing call chunk: ${result.fileName}\n" +
                        "Confidence: ${String.format("%.2f", result.confidence)}\n" +
                        "Explanation: ${result.llmExplanation}",
                metadata = mapOf(
                    "fileName" to result.fileName,
                    "confidence" to result.confidence.toString(),
                    "timestamp" to System.currentTimeMillis().toString(),
                    "source" to "CallRecordService"
                )
            )

            try {
                // 推送到 DataHub
                CoroutineScope(Dispatchers.IO).launch {
                    PhishingDataHub.addData(alert)
                }
                Log.i(TAG, "Phishing alert sent to DataHub for ${result.fileName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send phishing alert to DataHub", e)
            }
        }
    }
}
