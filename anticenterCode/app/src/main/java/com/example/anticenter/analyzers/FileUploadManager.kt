package com.example.anticenter.analyzers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.anticenter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Data class for file test results
 */
data class FileTestResult(
    val fileName: String,
    val groundTruth: String,
    val llmDecision: String,
    val confidence: Double,
    val llmExplanation: String,
    val processingTime: Double,
    val totalTokens: Int,
    val totalSteps: Int,
    val match: Boolean,
    val uploadTime: Double
)

/**
 * Enhanced File Upload Manager with file size validation
 */
class FileUploadManager(private val context: Context, private val apiKey: String) {

    companion object {
        private const val TAG = "FileUploadManager"
        private const val UPLOAD_URL = "https://api.dify.ai/v1/files/upload"
        private const val WORKFLOW_URL = "https://api.dify.ai/v1/workflows/run"
        private val USER_EMAIL = BuildConfig.DIFY_USER_EMAIL

        // Timeout settings
        private const val CONNECT_TIMEOUT = 90L
        private const val READ_TIMEOUT = 90L
        private const val WRITE_TIMEOUT = 90L

        // Minimum valid file size (100 bytes)
        private const val MIN_FILE_SIZE = 100L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            Log.d(TAG, "=== REQUEST ===")
            Log.d(TAG, "URL: ${request.url}")
            Log.d(TAG, "Method: ${request.method}")

            try {
                val response = chain.proceed(request)
                Log.d(TAG, "=== RESPONSE ===")
                Log.d(TAG, "Code: ${response.code}")
                Log.d(TAG, "Message: ${response.message}")
                response
            } catch (e: Exception) {
                Log.e(TAG, "Request failed", e)
                throw e
            }
        }
        .build()

    /**
     * Upload a file and immediately test it for phishing
     */
    suspend fun uploadAndTestFile(
        uri: Uri,
        fileName: String,
        groundTruth: String,
        onProgress: ((String) -> Unit)? = null
    ): FileTestResult? = withContext(Dispatchers.IO) {

        Log.d(TAG, "========================================")
        Log.d(TAG, "Starting upload and test for: $fileName")
        Log.d(TAG, "URI: $uri")
        Log.d(TAG, "========================================")

        onProgress?.invoke("Preparing $fileName...")

        try {
            // Validate API key
            if (apiKey.isBlank()) {
                Log.e(TAG, "ERROR: API key is blank!")
                onProgress?.invoke("Error: API key is missing")
                return@withContext null
            }

            // Step 1: Upload file
            Log.d(TAG, "STEP 1: Starting file upload...")
            val uploadStartTime = System.currentTimeMillis()
            val uploadId = uploadFile(uri, fileName, onProgress)
            val uploadTime = (System.currentTimeMillis() - uploadStartTime) / 1000.0

            if (uploadId == null) {
                Log.e(TAG, "ERROR: Upload failed - uploadId is null")
                onProgress?.invoke("❌ Failed to upload $fileName")
                return@withContext null
            }

            Log.d(TAG, "✓ File uploaded successfully")
            Log.d(TAG, "Upload ID: $uploadId")
            Log.d(TAG, "Upload time: ${uploadTime}s")

            // CRITICAL: Wait for Dify to process the file
            Log.d(TAG, "Waiting for file to be processed by Dify...")
            delay(2000) // Wait 2 seconds for Dify to process

            onProgress?.invoke("File uploaded. Testing...")

            // Step 2: Test the uploaded file
            Log.d(TAG, "STEP 2: Starting phishing detection...")
            val testStartTime = System.currentTimeMillis()
            val (response, testTime) = callPhishingApiWithFile(uploadId, onProgress)

            if (response == null) {
                Log.e(TAG, "ERROR: API call failed - response is null")
                onProgress?.invoke("❌ Failed to analyze $fileName")
                return@withContext null
            }

            Log.d(TAG, "✓ API response received")
            Log.d(TAG, "Test time: ${testTime}s")

            // Step 3: Parse response
            Log.d(TAG, "STEP 3: Parsing response...")
            val (decision, explanation, confidence) = parsePhishingResponse(response, onProgress)

            if (decision == null) {
                Log.e(TAG, "ERROR: Failed to parse response - decision is null")
                Log.e(TAG, "Full response: ${response.toString(2)}")
                onProgress?.invoke("❌ Failed to parse response for $fileName")
                return@withContext null
            }

            Log.d(TAG, "✓ Response parsed successfully")
            Log.d(TAG, "Decision: $decision")
            Log.d(TAG, "Confidence: $confidence")

            // Step 4: Calculate match
            val match = (decision == "phishing" && groundTruth.contains("Phishing", ignoreCase = true)) ||
                    (decision == "safe" && groundTruth.contains("Safe", ignoreCase = true))

            Log.d(TAG, "========================================")
            Log.d(TAG, "✓ Test completed successfully for $fileName")
            Log.d(TAG, "========================================")

            onProgress?.invoke("✓ Completed: $decision (${String.format("%.0f%%", confidence * 100)})")

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
        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "CRITICAL ERROR in uploadAndTestFile", e)
            Log.e(TAG, "========================================")
            onProgress?.invoke("Error: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Upload a file to Dify and return the upload ID
     * FIXED: Ensures file is fully written and flushed before upload
     */
    private suspend fun uploadFile(
        uri: Uri,
        fileName: String,
        onProgress: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {

        var tempFile: File? = null

        try {
            Log.d(TAG, "--- UPLOAD FILE START ---")
            Log.d(TAG, "URI: $uri")
            Log.d(TAG, "File name: $fileName")

            // Check if URI is accessible
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "ERROR: Cannot open input stream from URI")
                onProgress?.invoke("Error: Cannot access file")
                return@withContext null
            }

            Log.d(TAG, "✓ URI is accessible")

            // Create temp file with unique name
            val timestamp = System.currentTimeMillis()
            tempFile = File(context.cacheDir, "upload_${timestamp}_$fileName")

            Log.d(TAG, "Copying file to: ${tempFile.absolutePath}")

            // Copy with proper buffer and flush
            var totalBytesWritten = 0L
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192) // 8KB buffer
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesWritten += bytesRead
                    }

                    // CRITICAL: Ensure all data is written to disk
                    output.flush()
                    output.fd.sync() // Force sync to disk
                }
            }

            Log.d(TAG, "✓ Copied $totalBytesWritten bytes")

            // CRITICAL: Wait briefly to ensure file system has flushed
            delay(500)

            // Verify file exists and has content
            if (!tempFile.exists()) {
                Log.e(TAG, "ERROR: Temp file does not exist after copy!")
                return@withContext null
            }

            val fileSize = tempFile.length()
            Log.d(TAG, "✓ File verified")
            Log.d(TAG, "Final file size: $fileSize bytes (${fileSize / 1024.0} KB)")

            // Validate file size
            if (fileSize < MIN_FILE_SIZE) {
                Log.e(TAG, "ERROR: File is too small or empty (${fileSize} bytes)")
                Log.e(TAG, "Minimum required: $MIN_FILE_SIZE bytes")
                tempFile.delete()
                onProgress?.invoke("Error: File is too small or empty")
                return@withContext null
            }

            // Determine MIME type
            val mimeType = getMimeType(tempFile.extension.lowercase())
            Log.d(TAG, "MIME type: $mimeType")

            // Build multipart request
            Log.d(TAG, "Building multipart request...")
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("user", USER_EMAIL)
                .addFormDataPart(
                    "file",
                    fileName,
                    tempFile.asRequestBody(mimeType.toMediaType())
                )
                .build()

            val contentLength = requestBody.contentLength()
            Log.d(TAG, "Request body size: $contentLength bytes")

            if (contentLength <= 0) {
                Log.e(TAG, "ERROR: Request body is empty!")
                tempFile.delete()
                return@withContext null
            }

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            Log.d(TAG, "Sending upload request to: $UPLOAD_URL")
            onProgress?.invoke("Uploading ${fileSize / 1024}KB...")

            val response = client.newCall(request).execute()

            Log.d(TAG, "Upload response code: ${response.code}")

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                response.close()

                Log.d(TAG, "✓ Upload successful")
                Log.d(TAG, "Response: $responseBody")

                if (!responseBody.isNullOrEmpty()) {
                    val jsonResponse = JSONObject(responseBody)
                    val uploadId = jsonResponse.optString("id")

                    if (uploadId.isNotEmpty()) {
                        Log.d(TAG, "✓ Upload ID: $uploadId")

                        // Verify uploaded file size in response
                        val uploadedSize = jsonResponse.optInt("size", 0)
                        Log.d(TAG, "Server reported size: $uploadedSize bytes")

                        if (uploadedSize == 0) {
                            Log.w(TAG, "WARNING: Server reports file size as 0!")
                            Log.w(TAG, "This may cause workflow failures")
                        }

                        onProgress?.invoke("✓ Uploaded successfully")
                        tempFile.delete()
                        Log.d(TAG, "--- UPLOAD FILE END (SUCCESS) ---")
                        return@withContext uploadId
                    } else {
                        Log.e(TAG, "ERROR: Upload ID is empty")
                    }
                } else {
                    Log.e(TAG, "ERROR: Response body is empty")
                }
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "ERROR: Upload failed")
                Log.e(TAG, "HTTP ${response.code}: ${response.message}")
                Log.e(TAG, "Error: $errorBody")
                onProgress?.invoke("Upload failed: HTTP ${response.code}")
                response.close()
            }

            tempFile?.delete()
            Log.d(TAG, "--- UPLOAD FILE END (FAILED) ---")

        } catch (e: IOException) {
            Log.e(TAG, "ERROR: IOException", e)
            onProgress?.invoke("Network error: ${e.message}")
            tempFile?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "ERROR: Unexpected error", e)
            onProgress?.invoke("Error: ${e.message}")
            tempFile?.delete()
        }

        return@withContext null
    }

    /**
     * Upload file directly from File object (for internal use)
     * Used by CallRecordService
     */
    suspend fun uploadFileFromFile(file: File): Triple<String, String, Long>? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                Log.e(TAG, "ERROR: File does not exist: ${file.name}")
                return@withContext null
            }

            val fileSize = file.length()
            Log.d(TAG, "Uploading file: ${file.name}, size: $fileSize bytes")

            if (fileSize < MIN_FILE_SIZE) {
                Log.e(TAG, "ERROR: File too small: $fileSize bytes")
                return@withContext null
            }

            val mimeType = getMimeType(file.extension.lowercase())

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("user", USER_EMAIL)
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody(mimeType.toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                response.close()

                if (!responseBody.isNullOrEmpty()) {
                    val jsonResponse = JSONObject(responseBody)
                    val uploadId = jsonResponse.optString("id")
                    val signedUrl = jsonResponse.optString("url", "")
                    val uploadedSize = jsonResponse.optLong("size", fileSize)

                    if (uploadId.isNotEmpty()) {
                        Log.d(TAG, "✓ File uploaded: ID=$uploadId, Size=$uploadedSize")

                        if (uploadedSize == 0L) {
                            Log.w(TAG, "WARNING: Server reports size 0!")
                        }

                        return@withContext Triple(uploadId, signedUrl, uploadedSize)
                    }
                }
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "Upload failed: HTTP ${response.code} - $errorBody")
                response.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file", e)
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
            Log.d(TAG, "--- API CALL START ---")
            Log.d(TAG, "Upload ID: $uploadId")

            val payload = JSONObject().apply {
                put("inputs", JSONObject().apply {
                    put("InputVoice", JSONObject().apply {
                        put("transfer_method", "local_file")
                        put("upload_file_id", uploadId)
                        put("type", "audio")
                    })
                })
                put("response_mode", "blocking")
                put("user", USER_EMAIL)
            }

            Log.d(TAG, "Payload: ${payload.toString(2)}")

            val requestBody = RequestBody.create(
                "application/json".toMediaType(),
                payload.toString()
            )

            val request = Request.Builder()
                .url(WORKFLOW_URL)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            Log.d(TAG, "Calling workflow API...")
            onProgress?.invoke("Analyzing...")

            val startTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val processingTime = (System.currentTimeMillis() - startTime) / 1000.0

            Log.d(TAG, "Response code: ${response.code}")
            Log.d(TAG, "Processing time: ${processingTime}s")

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                response.close()

                Log.d(TAG, "✓ API call successful")
                Log.d(TAG, "Response (first 1000 chars): ${responseBody?.take(1000)}")

                if (!responseBody.isNullOrEmpty()) {
                    val jsonResponse = JSONObject(responseBody)
                    Log.d(TAG, "--- API CALL END (SUCCESS) ---")
                    return@withContext Pair(jsonResponse, processingTime)
                }
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "API call failed: HTTP ${response.code}")
                Log.e(TAG, "Error: $errorBody")
                onProgress?.invoke("API error: HTTP ${response.code}")
                response.close()
            }

            Log.d(TAG, "--- API CALL END (FAILED) ---")

        } catch (e: Exception) {
            Log.e(TAG, "ERROR in API call", e)
            onProgress?.invoke("Error: ${e.message}")
            return@withContext Pair(null, 0.0)
        }

        return@withContext Pair(null, 0.0)
    }

    /**
     * Call workflow with uploaded file details
     */
    suspend fun callWorkflowWithUploadedFile(
        uploadId: String,
        fileName: String,
        signedUrl: String,
        fileSize: Long
    ): Pair<JSONObject?, Double> {
        Log.d(TAG, "Calling workflow for: $fileName (size: $fileSize bytes)")

        if (fileSize == 0L) {
            Log.e(TAG, "ERROR: Cannot call workflow with 0-byte file!")
            return Pair(null, 0.0)
        }

        return callPhishingApiWithFile(uploadId) { msg ->
            Log.d(TAG, "Workflow: $msg")
        }
    }

    /**
     * Parse phishing detection response
     */
    private fun parsePhishingResponse(
        response: JSONObject?,
        onProgress: ((String) -> Unit)? = null
    ): Triple<String?, String?, Double> {

        if (response == null) {
            Log.e(TAG, "Response is null")
            return Triple(null, null, 0.0)
        }

        try {
            // Check for error
            if (response.has("message") && !response.has("data")) {
                val errorMessage = response.getString("message")
                Log.e(TAG, "API error: $errorMessage")
                onProgress?.invoke("Error: $errorMessage")
                return Triple(null, null, 0.0)
            }

            val data = response.optJSONObject("data") ?: return Triple(null, null, 0.0)

            // Check status
            val status = data.optString("status", "")
            if (status != "succeeded") {
                val error = data.optString("error", "Unknown error")
                Log.e(TAG, "Workflow failed: $error")
                onProgress?.invoke("Workflow failed: $error")
                return Triple(null, null, 0.0)
            }

            val outputs = data.optJSONObject("outputs") ?: return Triple(null, null, 0.0)

            // Find LLM output
            var llmOutput = outputs.optString("LLM", "")
            if (llmOutput.isEmpty()) {
                for (key in arrayOf("result", "output", "decision", "verdict", "response")) {
                    llmOutput = outputs.optString(key, "")
                    if (llmOutput.isNotEmpty()) break
                }
            }

            if (llmOutput.isEmpty()) {
                Log.e(TAG, "No LLM output found")
                return Triple(null, null, 0.0)
            }

            // Clean markdown
            var jsonContent = llmOutput.trim()
            if (jsonContent.contains("```json")) {
                jsonContent = jsonContent.substringAfter("```json").substringBefore("```").trim()
            } else if (jsonContent.startsWith("```")) {
                jsonContent = jsonContent.removePrefix("```").removeSuffix("```").trim()
            }

            // Parse JSON
            val resultJson = JSONObject(jsonContent)

            val verdict = resultJson.optString("verdict", "")
                .ifEmpty { resultJson.optString("decision", "") }
                .uppercase()

            val confidence = resultJson.optDouble("confidence", 0.0)

            val decision = when (verdict) {
                "PHISHING", "MALICIOUS", "SUSPICIOUS" -> "phishing"
                "SAFE", "LEGITIMATE", "BENIGN" -> "safe"
                else -> if (confidence >= 0.5) "phishing" else "safe"
            }

            // Build explanation
            val explanationParts = mutableListOf<String>()

            val reasons = resultJson.optJSONArray("reasons")
            if (reasons != null && reasons.length() > 0) {
                val reasonsList = mutableListOf<String>()
                for (i in 0 until reasons.length()) {
                    reasonsList.add(reasons.getString(i))
                }
                explanationParts.add("Reasons: ${reasonsList.joinToString("; ")}")
            }

            val evidence = resultJson.optJSONArray("evidence")
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

            Log.d(TAG, "✓ Parsed: decision=$decision, confidence=$confidence")

            return Triple(decision, explanation, confidence)

        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
            onProgress?.invoke("Parse error: ${e.message}")
            return Triple(null, null, 0.0)
        }
    }

    /**
     * Get MIME type based on file extension
     */
    private fun getMimeType(extension: String): String {
        return when (extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "txt" -> "text/plain"
            "eml" -> "message/rfc822"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}