package com.example.anticenter.services

import android.Manifest
import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.example.anticenter.BuildConfig
import com.example.anticenter.analyzers.FileTestResult

// ==================== Data Models ====================

data class PhishingMetrics(
    val accuracy: Double,
    val precision: Double,
    val recall: Double,
    val f1Score: Double,
    val truePositives: Int,
    val falsePositives: Int,
    val trueNegatives: Int,
    val falseNegatives: Int,
    val totalProcessingTime: Double,
    val avgProcessingTime: Double
)

// ==================== File Upload Manager ====================

class IntegratedFileUploadManager(
    private val context: Context,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "FileUploadManager"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val uploadUrl = "https://api.dify.ai/v1/files/upload"
    private val workflowUrl = "https://api.dify.ai/v1/workflows/run"
    private val userEmail = BuildConfig.DIFY_USER_EMAIL

    /**
     * Upload a file and immediately test it for phishing
     */
    suspend fun uploadAndTestFile(
        uri: Uri,
        fileName: String,
        groundTruth: String,
        onProgress: ((String) -> Unit)? = null
    ): FileTestResult? = withContext(Dispatchers.IO) {

        Log.d(TAG, "Starting upload and test for: $fileName")
        onProgress?.invoke("Uploading $fileName...")

        // Step 1: Upload file
        val uploadStartTime = System.currentTimeMillis()
        val uploadId = uploadFile(uri, fileName, onProgress)
        val uploadTime = (System.currentTimeMillis() - uploadStartTime) / 1000.0

        if (uploadId == null) {
            Log.e(TAG, "Upload failed for $fileName")
            onProgress?.invoke("Failed to upload $fileName")
            return@withContext null
        }

        Log.d(TAG, "File uploaded successfully. ID: $uploadId")
        onProgress?.invoke("File uploaded. Testing for phishing...")

        // Step 2: Test the uploaded file
        val testStartTime = System.currentTimeMillis()
        val (response, testTime) = callPhishingApiWithFile(uploadId, onProgress)

        if (response == null) {
            Log.e(TAG, "API call failed for $fileName")
            onProgress?.invoke("Failed to test $fileName")
            return@withContext null
        }

        Log.d(TAG, "API response received")

        // Step 3: Parse response
        val (decision, explanation, confidence) = parsePhishingResponse(response, onProgress)

        if (decision == null) {
            Log.e(TAG, "Failed to parse response for $fileName")
            onProgress?.invoke("Failed to parse response for $fileName")
            return@withContext null
        }

        // Step 4: Calculate match
        val match = (decision == "phishing" && groundTruth.contains("Phishing", ignoreCase = true)) ||
                (decision == "safe" && groundTruth.contains("Safe", ignoreCase = true))

        Log.d(TAG, "Test completed. Decision: $decision, Match: $match")
        onProgress?.invoke("✓ Completed $fileName: $decision")

        return@withContext FileTestResult(
            fileName = fileName,
            groundTruth = groundTruth,
            llmDecision = decision.uppercase(),
            confidence = confidence,
            llmExplanation = explanation ?: "No explanation provided",
            processingTime = testTime,
            totalTokens = response.optJSONObject("data")?.optInt("total_tokens", 0) ?: 0,
            totalSteps = response.optJSONObject("data")?.optInt("total_steps", 0) ?: 0,
            match = match,
            uploadTime = uploadTime
        )
    }

    /**
     * Upload a file to Dify and return the upload ID
     */
    private suspend fun uploadFile(
        uri: Uri,
        fileName: String,
        onProgress: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {

        try {
            Log.d(TAG, "Copying file from URI to temp file...")

            // Copy file from URI to temp file
            val tempFile = File(context.cacheDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "File copied. Size: ${tempFile.length()} bytes")

            // Determine MIME type
            val mimeType = when (tempFile.extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "txt" -> "text/plain"
                "eml" -> "message/rfc822"
                else -> "application/octet-stream"
            }

            Log.d(TAG, "MIME type: $mimeType")

            // Build multipart request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("user", userEmail)
                .addFormDataPart(
                    "file",
                    fileName,
                    tempFile.asRequestBody(mimeType.toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            Log.d(TAG, "Sending upload request to: $uploadUrl")

            val response = client.newCall(request).execute()

            Log.d(TAG, "Upload response code: ${response.code}")

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                response.close()
                tempFile.delete()

                Log.d(TAG, "Upload response: $responseBody")

                if (!responseBody.isNullOrEmpty()) {
                    val jsonResponse = JSONObject(responseBody)
                    val uploadId = jsonResponse.optString("id")

                    if (uploadId.isNotEmpty()) {
                        Log.d(TAG, "Upload successful. ID: $uploadId")
                        onProgress?.invoke("  ✓ Uploaded: $fileName")
                        return@withContext uploadId
                    }
                }
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "Upload failed: HTTP ${response.code} - $errorBody")
                onProgress?.invoke("  ✗ Upload failed: HTTP ${response.code}")
                response.close()
                tempFile.delete()
            }

        } catch (e: IOException) {
            Log.e(TAG, "Upload IO error", e)
            onProgress?.invoke("  ✗ Upload error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Upload unexpected error", e)
            onProgress?.invoke("  ✗ Unexpected error: ${e.message}")
        }

        return@withContext null
    }

    /**
     * Call phishing detection API with uploaded file ID
     */
    private suspend fun callPhishingApiWithFile(
        uploadId: String,
        onProgress: ((String) -> Unit)? = null
    ): Pair<JSONObject?, Double> = withContext(Dispatchers.IO) {

        try {
            val payload = JSONObject().apply {
                put("inputs", JSONObject().apply {
                    put("InputVoice", JSONObject().apply {
                        put("transfer_method", "local_file")
                        put("upload_file_id", uploadId)
                        put("type", "audio")
                    })
                })
                put("response_mode", "blocking")
                put("user", userEmail)
            }

            Log.d(TAG, "API payload: $payload")

            val requestBody = RequestBody.create(
                "application/json".toMediaType(),
                payload.toString()
            )

            val request = Request.Builder()
                .url(workflowUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            Log.d(TAG, "Sending API request to: $workflowUrl")

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val processingTime = (System.currentTimeMillis() - startTime) / 1000.0

            Log.d(TAG, "API response code: ${response.code}")

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                response.close()

                Log.d(TAG, "API response: ${responseBody?.take(500)}")

                if (!responseBody.isNullOrEmpty()) {
                    val jsonResponse = JSONObject(responseBody)
                    return@withContext Pair(jsonResponse, processingTime)
                }
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "API error: HTTP ${response.code} - $errorBody")
                onProgress?.invoke("  ✗ API error: HTTP ${response.code}")
                response.close()
            }
        } catch (e: IOException) {
            val processingTime = 0.0
            Log.e(TAG, "API IO error", e)
            onProgress?.invoke("  ✗ Network error: ${e.message}")
            return@withContext Pair(null, processingTime)
        } catch (e: Exception) {
            val processingTime = 0.0
            Log.e(TAG, "API unexpected error", e)
            onProgress?.invoke("  ✗ Unexpected error: ${e.message}")
            return@withContext Pair(null, processingTime)
        }

        return@withContext Pair(null, 0.0)
    }

    /**
     * Parse phishing detection response
     */
    private fun parsePhishingResponse(
        response: JSONObject?,
        onProgress: ((String) -> Unit)? = null
    ): Triple<String?, String?, Double> {

        if (response == null) {
            return Triple(null, null, 0.0)
        }

        try {
            // Check for error response
            if (response.has("message") && !response.has("data")) {
                Log.e(TAG, "API Error: ${response.getString("message")}")
                onProgress?.invoke("API Error: ${response.getString("message")}")
                return Triple(null, null, 0.0)
            }

            val data = response.optJSONObject("data") ?: return Triple(null, null, 0.0)

            // Check workflow status
            val status = data.optString("status", "")
            if (status != "succeeded") {
                Log.e(TAG, "Workflow failed: ${data.optString("error", "Unknown error")}")
                onProgress?.invoke("Workflow failed: ${data.optString("error", "Unknown error")}")
                return Triple(null, null, 0.0)
            }

            val outputs = data.optJSONObject("outputs") ?: return Triple(null, null, 0.0)

            // Try to find LLM output
            var llmOutput = outputs.optString("LLM", "")
            if (llmOutput.isEmpty()) {
                // Try alternative keys
                for (key in arrayOf("result", "output", "decision", "verdict", "response")) {
                    llmOutput = outputs.optString(key, "")
                    if (llmOutput.isNotEmpty()) break
                }
            }

            if (llmOutput.isEmpty()) {
                Log.e(TAG, "No LLM output found")
                return Triple(null, null, 0.0)
            }

            Log.d(TAG, "LLM output: ${llmOutput.take(200)}")

            // Remove markdown code blocks
            var jsonContent = llmOutput.trim()
            if (jsonContent.contains("```json")) {
                jsonContent = jsonContent.substringAfter("```json").substringBefore("```").trim()
            } else if (jsonContent.startsWith("```")) {
                jsonContent = jsonContent.removePrefix("```").removeSuffix("```").trim()
            }

            // Parse the JSON
            val resultJson = JSONObject(jsonContent)

            // Extract verdict/decision
            val verdict = resultJson.optString("verdict", "")
                .ifEmpty { resultJson.optString("decision", "") }
                .uppercase()

            val confidence = resultJson.optDouble("confidence", 0.0)

            // Determine decision
            val decision = when (verdict) {
                "PHISHING", "MALICIOUS", "SUSPICIOUS" -> "phishing"
                "SAFE", "LEGITIMATE", "BENIGN" -> "safe"
                else -> {
                    // Use confidence as fallback
                    if (confidence >= 0.5) "phishing" else "safe"
                }
            }

            // Build explanation
            val reasons = resultJson.optJSONArray("reasons")
            val evidence = resultJson.optJSONArray("evidence")

            val explanationParts = mutableListOf<String>()

            if (reasons != null && reasons.length() > 0) {
                val reasonsList = mutableListOf<String>()
                for (i in 0 until reasons.length()) {
                    reasonsList.add(reasons.getString(i))
                }
                explanationParts.add("Reasons: ${reasonsList.joinToString("; ")}")
            }

            if (evidence != null && evidence.length() > 0) {
                val evidenceList = mutableListOf<String>()
                for (i in 0 until evidence.length()) {
                    val item = evidence.get(i)
                    when (item) {
                        is JSONObject -> {
                            val quote = item.optString("quote", "")
                            val tactic = item.optString("tactic", "")
                            if (quote.isNotEmpty() && tactic.isNotEmpty()) {
                                evidenceList.add("'$quote' ($tactic)")
                            } else if (quote.isNotEmpty()) {
                                evidenceList.add(quote)
                            }
                        }
                        is String -> evidenceList.add(item)
                    }
                }
                if (evidenceList.isNotEmpty()) {
                    explanationParts.add("Evidence: ${evidenceList.joinToString("; ")}")
                }
            }

            val explanation = if (explanationParts.isNotEmpty()) {
                explanationParts.joinToString(" | ")
            } else {
                "Confidence: ${String.format("%.2f", confidence)}"
            }

            Log.d(TAG, "Parsed - Decision: $decision, Confidence: $confidence")

            return Triple(decision, explanation, confidence)

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response", e)
            onProgress?.invoke("Error parsing response: ${e.message}")
            return Triple(null, null, 0.0)
        }
    }
}

// ==================== Call Recording Service ====================

class CallRecordService : Service() {

    companion object {
        private const val TAG = "CallRecordService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "call_record_channel"

        // Audio settings
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val CHANNELS = 1
        private const val CHUNK_SECONDS = 20

        // API key - 从 BuildConfig 读取（不能用 const，因为不是编译时常量）
        private val DIFY_API_KEY = BuildConfig.DIFY_API_KEY

        // Detection thresholds
        private const val PHISHING_CONFIDENCE_THRESHOLD = 0.7
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AudioRecord? = null
    private val recording = AtomicBoolean(false)

    private lateinit var fileUploadManager: IntegratedFileUploadManager
    private var recordingCount = 0

    // Statistics
    private var totalChunksProcessed = 0
    private var phishingDetected = 0
    private val detectionResults = mutableListOf<FileTestResult>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Initialize FileUploadManager with API key
        fileUploadManager = IntegratedFileUploadManager(applicationContext, DIFY_API_KEY)

        startInForeground()
        startRecording()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroying")
        printStatistics()
        stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * Start service in foreground with persistent notification
     */
    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Recording and analyzing phone calls"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Phishing Detection Active")
            .setContentText("Recording and analyzing calls...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Android 14+ requires specifying foreground service type
        // Use PHONE_CALL only - it has exemptions during actual calls
        // MICROPHONE requires app to be visible, which we aren't during incoming calls
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Started in foreground")
    }

    /**
     * Update notification with current status
     */
    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Phishing Detection Active")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Start audio recording
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        if (recording.get()) {
            Log.w(TAG, "Already recording")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size: $minBufferSize")
            return
        }

        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNELS)

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                recorder?.release()
                recorder = null
                return
            }

            recorder?.startRecording()
            recording.set(true)

            Log.d(TAG, "Recording started with buffer size: $bufferSize")

            // Start recording loop in coroutine
            scope.launch { recordLoop(bufferSize) }

        } catch (e: SecurityException) {
            Log.e(TAG, "No audio recording permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
        }
    }

    /**
     * Main recording loop - reads audio data and splits into chunks
     */
    private suspend fun recordLoop(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        var chunkStartTime = System.currentTimeMillis()
        var currentOutputStream: FileOutputStream? = null
        var currentRawFile: File? = null
        var bytesWritten = 0L

        try {
            while (recording.get()) {
                val bytesRead = recorder?.read(buffer, 0, buffer.size) ?: 0

                when {
                    bytesRead > 0 -> {
                        val now = System.currentTimeMillis()

                        // Create new file if needed
                        if (currentOutputStream == null) {
                            val file = createNewPcmFile()
                            currentRawFile = file
                            currentOutputStream = FileOutputStream(file)
                            chunkStartTime = now
                            bytesWritten = 0
                            Log.d(TAG, "Started new chunk: ${file.name}")
                        }

                        // Write audio data
                        currentOutputStream!!.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead

                        // Check if chunk duration reached
                        val elapsed = now - chunkStartTime
                        if (elapsed >= CHUNK_SECONDS * 1000L) {
                            Log.d(TAG, "Chunk complete: ${bytesWritten} bytes, ${elapsed}ms")

                            // Close current file
                            currentOutputStream?.flush()
                            currentOutputStream?.close()
                            currentOutputStream = null

                            // Process the completed chunk
                            currentRawFile?.let { file ->
                                processAndUploadChunk(file)
                            }
                            currentRawFile = null
                        }
                    }
                    bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "Invalid operation error")
                        delay(100)
                    }
                    bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "Bad value error")
                        delay(100)
                    }
                    else -> {
                        delay(10)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in record loop", e)
        } finally {
            // Clean up current recording
            try {
                currentOutputStream?.flush()
                currentOutputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing stream", e)
            }

            // Process final chunk if exists
            currentRawFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    processAndUploadChunk(file)
                }
            }

            Log.d(TAG, "Record loop ended")
        }
    }

    /**
     * Stop recording
     */
    private fun stopRecording() {
        if (!recording.get()) {
            return
        }

        Log.d(TAG, "Stopping recording")
        recording.set(false)

        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recorder: ${e.message}")
        } finally {
            recorder = null
        }
    }

    /**
     * Get directory for storing audio slices
     */
    private fun getSlicesDirectory(): File {
        return File(filesDir, "call_slices").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    /**
     * Create new PCM file for recording
     */
    private fun createNewPcmFile(): File {
        recordingCount++
        val timestamp = System.currentTimeMillis()
        return File(getSlicesDirectory(), "call_${timestamp}_${recordingCount}.pcm")
    }

    /**
     * Process PCM chunk: convert to WAV and upload
     */
    private fun processAndUploadChunk(pcmFile: File) {
        scope.launch {
            try {
                Log.d(TAG, "Processing chunk: ${pcmFile.name}, size: ${pcmFile.length()}")

                // Convert PCM to WAV
                val wavFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".wav")
                convertPcmToWav(pcmFile, wavFile)

                // Delete PCM file after conversion
                pcmFile.delete()

                // Wait briefly to ensure file is written
                delay(200)

                // Verify WAV file
                val fileSize = wavFile.length()
                if (fileSize <= 44) { // WAV header is 44 bytes
                    Log.w(TAG, "WAV file too small or empty: ${wavFile.name}, size: $fileSize")
                    wavFile.delete()
                    return@launch
                }

                Log.d(TAG, "WAV file created: ${wavFile.name}, size: $fileSize bytes")

                // Upload and test the WAV file
                uploadAndAnalyzeChunk(wavFile)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing chunk", e)
            }
        }
    }

    /**
     * Convert PCM to WAV format
     */
    private fun convertPcmToWav(pcmFile: File, wavFile: File) {
        val pcmSize = pcmFile.length().toInt()
        val totalDataSize = pcmSize + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE

        DataOutputStream(BufferedOutputStream(FileOutputStream(wavFile))).use { out ->
            // RIFF header
            out.writeBytes("RIFF")
            out.writeIntLE(totalDataSize)
            out.writeBytes("WAVE")

            // fmt subchunk
            out.writeBytes("fmt ")
            out.writeIntLE(16) // Subchunk size
            out.writeShortLE(1) // Audio format (PCM)
            out.writeShortLE(CHANNELS.toShort())
            out.writeIntLE(SAMPLE_RATE)
            out.writeIntLE(byteRate)
            out.writeShortLE((CHANNELS * BYTES_PER_SAMPLE).toShort()) // Block align
            out.writeShortLE(16) // Bits per sample

            // data subchunk
            out.writeBytes("data")
            out.writeIntLE(pcmSize)

            // Copy PCM data
            FileInputStream(pcmFile).use { input ->
                input.copyTo(out)
            }

            out.flush()
        }
    }

    /**
     * Upload WAV file and analyze for phishing
     */
    private suspend fun uploadAndAnalyzeChunk(wavFile: File) {
        try {
            Log.d(TAG, "Uploading and analyzing: ${wavFile.name}")
            updateNotification("Analyzing chunk ${totalChunksProcessed + 1}...")

            // Create URI from file
            val uri = Uri.fromFile(wavFile)

            // Upload and test using FileUploadManager
            val result = fileUploadManager.uploadAndTestFile(
                uri = uri,
                fileName = wavFile.name,
                groundTruth = "Unknown",
                onProgress = { message ->
                    Log.d(TAG, "Upload progress: $message")
                }
            )

            if (result != null) {
                totalChunksProcessed++
                detectionResults.add(result)

                Log.i(TAG, "Analysis complete for ${wavFile.name}")
                Log.i(TAG, "Decision: ${result.llmDecision}")
                Log.i(TAG, "Confidence: ${String.format("%.2f", result.confidence)}")
                Log.i(TAG, "Explanation: ${result.llmExplanation}")

                // Update notification with current status
                val statusText = "Analyzed: $totalChunksProcessed chunks | Phishing: $phishingDetected"
                updateNotification(statusText)

                // Show alert if phishing detected with high confidence
                if (result.llmDecision.uppercase() == "PHISHING" &&
                    result.confidence > PHISHING_CONFIDENCE_THRESHOLD) {
                    phishingDetected++
                    showPhishingAlert(result)
                }

                // Save results periodically
                if (totalChunksProcessed % 5 == 0) {
                    saveDetectionResults()
                }

                // Delete WAV file after successful processing
                wavFile.delete()
                Log.d(TAG, "Deleted processed file: ${wavFile.name}")

            } else {
                Log.e(TAG, "Failed to analyze ${wavFile.name}")
                // Keep file for retry or manual inspection
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error uploading/analyzing chunk", e)
        }
    }

    /**
     * Show alert notification when phishing is detected
     */
    private fun showPhishingAlert(result: FileTestResult) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val alertNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Phishing Call Detected!")
            .setContentText("Confidence: ${String.format("%.0f%%", result.confidence * 100)}")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${result.llmExplanation}\n\nBe cautious and do not share personal information."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + phishingDetected, alertNotification)

        Log.w(TAG, "PHISHING ALERT #$phishingDetected: ${result.fileName} - Confidence: ${result.confidence}")
    }

    /**
     * Save detection results to CSV file
     */
    private fun saveDetectionResults() {
        scope.launch {
            try {
                val resultsDir = File(filesDir, "detection_results")
                if (!resultsDir.exists()) {
                    resultsDir.mkdirs()
                }

                val timestamp = System.currentTimeMillis()
                val csvFile = File(resultsDir, "results_$timestamp.csv")

                FileWriter(csvFile).use { writer ->
                    // Write header
                    writer.append("Timestamp,FileName,Decision,Confidence,Explanation,UploadTime,ProcessingTime,TotalTokens,TotalSteps\n")

                    // Write data
                    detectionResults.forEach { result ->
                        writer.append("$timestamp,")
                        writer.append("\"${result.fileName}\",")
                        writer.append("\"${result.llmDecision}\",")
                        writer.append("${result.confidence},")
                        writer.append("\"${result.llmExplanation.replace("\"", "\"\"")}\",")
                        writer.append("${result.uploadTime},")
                        writer.append("${result.processingTime},")
                        writer.append("${result.totalTokens},")
                        writer.append("${result.totalSteps}\n")
                    }
                }

                Log.d(TAG, "Saved results to ${csvFile.absolutePath}")

            } catch (e: Exception) {
                Log.e(TAG, "Error saving results", e)
            }
        }
    }

    /**
     * Print statistics to log
     */
    private fun printStatistics() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "CALL RECORDING SESSION STATISTICS")
        Log.i(TAG, "========================================")
        Log.i(TAG, "Total chunks processed: $totalChunksProcessed")
        Log.i(TAG, "Phishing detected: $phishingDetected")

        if (detectionResults.isNotEmpty()) {
            val avgConfidence = detectionResults.map { it.confidence }.average()
            val avgProcessingTime = detectionResults.map { it.processingTime + it.uploadTime }.average()
            val totalTokens = detectionResults.sumOf { it.totalTokens }

            Log.i(TAG, "Average confidence: ${String.format("%.2f", avgConfidence)}")
            Log.i(TAG, "Average processing time: ${String.format("%.2f", avgProcessingTime)}s")
            Log.i(TAG, "Total tokens used: $totalTokens")

            val phishingRate = (phishingDetected.toDouble() / totalChunksProcessed * 100)
            Log.i(TAG, "Phishing detection rate: ${String.format("%.1f%%", phishingRate)}")
        }

        Log.i(TAG, "========================================")
    }

    /**
     * Helper extension functions for writing little-endian values
     */
    private fun DataOutputStream.writeIntLE(value: Int) {
        write(byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        ))
    }

    private fun DataOutputStream.writeShortLE(value: Short) {
        write(byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            (value.toInt() shr 8 and 0xFF).toByte()
        ))
    }
}

// ==================== Phishing Analyzer ====================

class IntegratedPhishingAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "PhishingAnalyzer"
    }

    data class AnalysisResult(
        val totalSamples: Int,
        val phishingSamples: Int,
        val safeSamples: Int,
        val metrics: PhishingMetrics,
        val misclassifications: MisclassificationDetails
    )

    data class MisclassificationDetails(
        val falseNegatives: List<FileTestResult>,
        val falsePositives: List<FileTestResult>
    )

    /**
     * Analyze phishing detection results
     */
    fun analyzeResults(results: List<FileTestResult>): AnalysisResult {
        val phishingResults = results.filter {
            it.groundTruth.contains("Phishing", ignoreCase = true)
        }
        val safeResults = results.filter {
            it.groundTruth.contains("Safe", ignoreCase = true)
        }

        // Get misclassifications
        val falseNegatives = phishingResults.filter {
            it.llmDecision.equals("SAFE", ignoreCase = true)
        }
        val falsePositives = safeResults.filter {
            it.llmDecision.equals("PHISHING", ignoreCase = true)
        }

        val metrics = calculateMetrics(results)

        return AnalysisResult(
            totalSamples = results.size,
            phishingSamples = phishingResults.size,
            safeSamples = safeResults.size,
            metrics = metrics,
            misclassifications = MisclassificationDetails(falseNegatives, falsePositives)
        )
    }

    /**
     * Calculate classification metrics
     */
    private fun calculateMetrics(results: List<FileTestResult>): PhishingMetrics {
        var tp = 0 // True Positives
        var tn = 0 // True Negatives
        var fp = 0 // False Positives
        var fn = 0 // False Negatives

        results.forEach { result ->
            val isPhishingGT = result.groundTruth.contains("Phishing", ignoreCase = true)
            val isPhishingPred = result.llmDecision.equals("PHISHING", ignoreCase = true)

            when {
                isPhishingGT && isPhishingPred -> tp++
                !isPhishingGT && !isPhishingPred -> tn++
                !isPhishingGT && isPhishingPred -> fp++
                isPhishingGT && !isPhishingPred -> fn++
            }
        }

        val accuracy = if (results.isNotEmpty())
            (tp + tn).toDouble() / results.size else 0.0

        val precision = if (tp + fp > 0)
            tp.toDouble() / (tp + fp) else 0.0

        val recall = if (tp + fn > 0)
            tp.toDouble() / (tp + fn) else 0.0

        val f1Score = if (precision + recall > 0)
            2 * (precision * recall) / (precision + recall) else 0.0

        val totalTime = results.sumOf { it.processingTime + it.uploadTime }
        val avgTime = if (results.isNotEmpty())
            totalTime / results.size else 0.0

        return PhishingMetrics(
            accuracy = accuracy,
            precision = precision,
            recall = recall,
            f1Score = f1Score,
            truePositives = tp,
            falsePositives = fp,
            trueNegatives = tn,
            falseNegatives = fn,
            totalProcessingTime = totalTime,
            avgProcessingTime = avgTime
        )
    }

    /**
     * Generate text report
     */
    fun generateTextReport(analysis: AnalysisResult): String {
        val sb = StringBuilder()

        sb.appendLine("=" .repeat(80))
        sb.appendLine("PHISHING DETECTION RESULTS ANALYSIS")
        sb.appendLine("=" .repeat(80))
        sb.appendLine()

        sb.appendLine("Total Samples: ${analysis.totalSamples}")
        sb.appendLine("Phishing Calls: ${analysis.phishingSamples}")
        sb.appendLine("Safe Calls: ${analysis.safeSamples}")
        sb.appendLine()

        with(analysis.metrics) {
            sb.appendLine("PERFORMANCE METRICS")
            sb.appendLine("-".repeat(80))
            sb.appendLine("Accuracy:  ${String.format("%.2f%%", accuracy * 100)}")
            sb.appendLine("Precision: ${String.format("%.2f%%", precision * 100)}")
            sb.appendLine("Recall:    ${String.format("%.2f%%", recall * 100)}")
            sb.appendLine("F1 Score:  ${String.format("%.3f", f1Score)}")
            sb.appendLine()

            sb.appendLine("CONFUSION MATRIX")
            sb.appendLine("-".repeat(80))
            sb.appendLine("True Positives:  $truePositives")
            sb.appendLine("True Negatives:  $trueNegatives")
            sb.appendLine("False Positives: $falsePositives")
            sb.appendLine("False Negatives: $falseNegatives")
            sb.appendLine()

            sb.appendLine("TIMING")
            sb.appendLine("-".repeat(80))
            sb.appendLine("Total Time: ${String.format("%.2f", totalProcessingTime)}s")
            sb.appendLine("Average per Call: ${String.format("%.2f", avgProcessingTime)}s")
        }

        if (analysis.misclassifications.falseNegatives.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("⚠️ WARNING: ${analysis.misclassifications.falseNegatives.size} phishing calls not detected!")
        }

        if (analysis.misclassifications.falsePositives.isNotEmpty()) {
            sb.appendLine("⚠️ WARNING: ${analysis.misclassifications.falsePositives.size} safe calls incorrectly flagged!")
        }

        return sb.toString()
    }

    /**
     * Save results to CSV file
     */
    fun saveResultsToCsv(results: List<FileTestResult>, filename: String = "phishing_results.csv"): File? {
        try {
            val file = File(context.getExternalFilesDir(null), filename)
            val writer = FileWriter(file)

            // Write header
            writer.append("FileName,GroundTruth,Decision,Confidence,Explanation,Match,UploadTime,ProcessingTime,TotalTokens,TotalSteps\n")

            // Write data
            results.forEach { result ->
                writer.append("\"${result.fileName}\",")
                writer.append("\"${result.groundTruth}\",")
                writer.append("\"${result.llmDecision}\",")
                writer.append("${result.confidence},")
                writer.append("\"${result.llmExplanation.replace("\"", "\"\"")}\",")
                writer.append("${result.match},")
                writer.append("${result.uploadTime},")
                writer.append("${result.processingTime},")
                writer.append("${result.totalTokens},")
                writer.append("${result.totalSteps}\n")
            }

            writer.close()
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Error saving results to CSV", e)
            return null
        }
    }

    /**
     * Save metrics to CSV file
     */
    fun saveMetricsToCsv(metrics: PhishingMetrics, filename: String = "phishing_metrics.csv"): File? {
        try {
            val file = File(context.getExternalFilesDir(null), filename)
            val writer = FileWriter(file)

            // Write header
            writer.append("accuracy,precision,recall,f1_score,true_positives,false_positives,true_negatives,false_negatives,total_processing_time,avg_processing_time\n")

            // Write data
            writer.append("${metrics.accuracy},")
            writer.append("${metrics.precision},")
            writer.append("${metrics.recall},")
            writer.append("${metrics.f1Score},")
            writer.append("${metrics.truePositives},")
            writer.append("${metrics.falsePositives},")
            writer.append("${metrics.trueNegatives},")
            writer.append("${metrics.falseNegatives},")
            writer.append("${metrics.totalProcessingTime},")
            writer.append("${metrics.avgProcessingTime}\n")

            writer.close()
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Error saving metrics to CSV", e)
            return null
        }
    }
}

// ==================== Helper Classes ====================

/**
 * Collector interface for starting/stopping call recording
 */
class PhoneCallCollector(
    private val context: Context,
    private val dataHub: Any? = null
) {
    companion object {
        private const val TAG = "PhoneCallCollector"
    }

    private var serviceIntent: Intent? = null

    /**
     * Start call recording service
     */
    suspend fun startCollection() {
        try {
            serviceIntent = Intent(context, CallRecordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "Call recording service started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting call recording service", e)
            throw e
        }
    }

    /**
     * Stop call recording service
     */
    fun stopCollection() {
        try {
            serviceIntent?.let { intent ->
                context.stopService(intent)
                Log.d(TAG, "Call recording service stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping call recording service", e)
        }
    }
}

/**
 * Extension function to format time duration
 */
fun formatTime(seconds: Double): String {
    return when {
        seconds < 60 -> String.format("%.2f seconds", seconds)
        seconds < 3600 -> {
            val minutes = (seconds / 60).toInt()
            val secs = seconds % 60
            String.format("%dm %.2fs", minutes, secs)
        }
        else -> {
            val hours = (seconds / 3600).toInt()
            val minutes = ((seconds % 3600) / 60).toInt()
            val secs = seconds % 60
            String.format("%dh %dm %.2fs", hours, minutes, secs)
        }
    }
}