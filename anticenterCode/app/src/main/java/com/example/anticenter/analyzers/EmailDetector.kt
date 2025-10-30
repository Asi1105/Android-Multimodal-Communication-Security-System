package com.example.anticenter.analyzers

import com.example.anticenter.BuildConfig
import com.example.anticenter.data.PhishingData // Import for PhishingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.text.get

class EmailDetector(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiUrl = "https://api.dify.ai/v1/workflows/run"
    private val jsonMediaType = "application/json".toMediaType()

    private fun cleanEmailText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var cleanedText = text.trim()
        cleanedText = cleanedText.replace(Regex("\\s+"), " ")
        cleanedText = cleanedText.filter { char -> char.code >= 32 || char in "\n\r\t" }
        cleanedText = cleanedText
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        val maxLength = 4000
        if (cleanedText.length > maxLength) {
            cleanedText = cleanedText.substring(0, maxLength) + "... [truncated]"
        }
        return cleanedText
    }

    // Renamed from callPhishingApi to avoid confusion with the previous version if it's still around
    // and made internal as it's an implementation detail of this detector.
    internal suspend fun fetchPhishingPredictionFromApi(
        emailText: String,
        onDebug: ((String) -> Unit)? = null
    ): Pair<JSONObject?, Double> = withContext(Dispatchers.IO) {
        val cleanedText = cleanEmailText(emailText)
        if (cleanedText.isEmpty()) {
            onDebug?.invoke("EmailDetector: Warning: Empty email text after cleaning for API call")
            return@withContext Pair(null, 0.0)
        }

        val payload = JSONObject().apply {
            put("inputs", JSONObject().apply { put("InputText", cleanedText) })
            put("response_mode", "blocking")
            put("user", BuildConfig.DIFY_USER_EMAIL)
        }
        onDebug?.invoke("EmailDetector: Sending API request with payload: ${payload.toString().take(200)}")

        val requestBody = payload.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val startTime = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response -> // Use 'use' to ensure closure
                val processingTime = (System.currentTimeMillis() - startTime) / 1000.0
                onDebug?.invoke("EmailDetector: API Response code: ${response.code}")
                val responseBodyString = response.body?.string() // Read body once

                if (response.isSuccessful) {
                    onDebug?.invoke("EmailDetector: API Response body: ${responseBodyString?.take(500)}")
                    if (!responseBodyString.isNullOrEmpty()) {
                        return@withContext Pair(JSONObject(responseBodyString), processingTime)
                    } else {
                        onDebug?.invoke("EmailDetector: API returned empty successful response body")
                        return@withContext Pair(null, processingTime) // Or handle as error
                    }
                } else {
                    onDebug?.invoke("EmailDetector: API Error: HTTP ${response.code} - ${responseBodyString?.take(500)}")
                    return@withContext Pair(null, processingTime) // Return time even on error
                }
            }
        } catch (e: IOException) {
            val processingTime = (System.currentTimeMillis() - startTime) / 1000.0
            onDebug?.invoke("EmailDetector: Network error during API call: ${e.message}")
            return@withContext Pair(null, processingTime)
        } catch (e: Exception) {
            val processingTime = (System.currentTimeMillis() - startTime) / 1000.0
            onDebug?.invoke("EmailDetector: Unexpected error during API call: ${e.message}")
            e.printStackTrace()
            return@withContext Pair(null, processingTime)
        }
    }

    // Made internal as it's an implementation detail.
    internal fun parseLlmApiResponse(
        response: JSONObject?,
        onDebug: ((String) -> Unit)? = null
    ): Pair<String?, String?> { // (Decision String, Explanation String)
        if (response == null) {
            onDebug?.invoke("EmailDetector: Error: Null JSON object received for parsing LLM response.")
            return Pair(null, "Received null JSON object for LLM response")
        }
        try {
            onDebug?.invoke("EmailDetector: Parsing LLM API response object: ${response.toString().take(500)}")

            if (response.has("message") && !response.has("data")) {
                val errorMessage = response.getString("message")
                onDebug?.invoke("EmailDetector: LLM API top-level error: $errorMessage")
                return Pair(null, "LLM API Error: $errorMessage")
            }
            if (!response.has("data")) {
                onDebug?.invoke("EmailDetector: Error: No 'data' field in LLM API response")
                return Pair(null, "No 'data' field in LLM API response")
            }
            val data = response.getJSONObject("data")
            val status = data.optString("status", "")
            if (status != "succeeded") {
                val error = data.optString("error", "Unknown workflow error")
                onDebug?.invoke("EmailDetector: Error: LLM Workflow status is '$status'. Error: $error")
                return Pair(null, "LLM Workflow Error ($status): $error")
            }
            if (!data.has("outputs")) {
                onDebug?.invoke("EmailDetector: Error: No 'outputs' field in LLM data")
                return Pair(null, "No 'outputs' field in LLM data")
            }
            val outputs = data.getJSONObject("outputs")
            if (!outputs.has("LLM")) { // Assuming "LLM" is the key for the detailed output
                onDebug?.invoke("EmailDetector: Error: No 'LLM' text output field in outputs")
                return Pair(null, "No 'LLM' text output field in outputs")
            }
            val llmOutputText = outputs.getString("LLM").trim()
            onDebug?.invoke("EmailDetector: Raw LLM text output received: \"$llmOutputText\"")

            // Attempt to handle TRUE:/FALSE: prefix before JSON
            if (llmOutputText.startsWith("TRUE:", ignoreCase = true)) {
                val explanation = llmOutputText.substringAfter("TRUE:").trim()
                onDebug?.invoke("EmailDetector: LLM output parsed as TRUE prefix. Decision: phishing, Explanation: \"$explanation\"")
                return Pair("phishing", explanation)
            } else if (llmOutputText.startsWith("FALSE:", ignoreCase = true)) {
                val explanation = llmOutputText.substringAfter("FALSE:").trim()
                onDebug?.invoke("EmailDetector: LLM output parsed as FALSE prefix. Decision: safe, Explanation: \"$explanation\"")
                return Pair("safe", explanation)
            }

            onDebug?.invoke("EmailDetector: LLM output not TRUE/FALSE prefix. Attempting JSON parsing from LLM text...")
            val jsonContent = if (llmOutputText.contains("```json")) {
                llmOutputText.substringAfter("```json").substringBefore("```").trim()
            } else if (llmOutputText.startsWith("{") && llmOutputText.endsWith("}")) {
                llmOutputText
            } else {
                onDebug?.invoke("EmailDetector: Error: LLM text output is not in expected JSON format (```json...``` or {...}) nor TRUE:/FALSE: prefix. Content: \"$llmOutputText\"")
                return Pair(null, "LLM text output not in a recognized format: \"$llmOutputText\"")
            }

            val resultJson = JSONObject(jsonContent)
            val decision = resultJson.optString("decision", "").lowercase()
            val notes = resultJson.optString("notes_200char_max", "")
            val likelihood = resultJson.optInt("likelihood", -1) // Default to -1 if not found
            val therefore = resultJson.optString("therefore", "")

            val explanation = buildString {
                append(notes)
                if (therefore.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append(therefore)
                }
                append(" (Likelihood: $likelihood)")
            }.trim()

            onDebug?.invoke("EmailDetector: LLM JSON parsed. Decision: '$decision', Explanation: '$explanation'")

            // Standardize decision based on parsed JSON
            // This mapping is now effectively a fallback if TRUE:/FALSE: wasn't hit
            val mappedDecision = when (decision) {
                "benign" -> "safe"
                "phishing" -> "phishing"
                "spam" -> "safe"
                else -> if (likelihood >= 5) "phishing" else "safe"
            }
            return Pair(mappedDecision, explanation)

        } catch (e: Exception) {
            onDebug?.invoke("EmailDetector: Error parsing LLM response: ${e.message}")
            e.printStackTrace()
            return Pair(null, "Error parsing LLM response: ${e.message}")
        }
    }

    /**
     * Normalize textual decision to binary values (0 for safe, 1 for phishing).
     * The groundTruth parameter is kept for consistency with original but not used in live analysis.
     * Returns a Pair: (predictedBinary: Int?, trueBinary: Int)
     * predictedBinary can be null if the decision string is not recognized.
     */
    internal fun normalizeDecision(
        decision: String?,
        groundTruth: String, // Kept for signature consistency, but ignored in this context
        onDebug: ((String) -> Unit)? = null
    ): Pair<Int?, Int> {
        // Normalize ground truth (ignored for live prediction, but calculated for completeness)
        val gtLower = groundTruth.lowercase().trim()
        val trueBinary = if ("phishing" in gtLower) 1 else 0 // Simple ground truth mapping

        if (decision == null) {
            onDebug?.invoke("EmailDetector: normalizeDecision: Received null decision.")
            return Pair(null, trueBinary)
        }

        val decisionLower = decision.lowercase().trim()
        val predictedBinary = when (decisionLower) {
            "phishing" -> 1
            "safe" -> 0
            else -> {
                onDebug?.invoke("EmailDetector: normalizeDecision: Warning: Unexpected decision value: '$decisionLower'")
                null // Unknown decision
            }
        }
        onDebug?.invoke("EmailDetector: normalizeDecision: Text decision '$decisionLower' -> binary $predictedBinary (GT was '$groundTruth' -> $trueBinary)")
        return Pair(predictedBinary, trueBinary)
    }

    /**
     * Analyzes PhishingData (expected to be an email) to determine if it's phishing.
     *
     * @param phishingData The PhishingData object containing the email content.
     * @param onDebug Optional callback for debug messages.
     * @return Pair<Boolean, String?>:
     *         - Boolean: true if classified as phishing, false otherwise or on error.
     *         - String?: Explanation for the decision, or an error message.
     */
    suspend fun analyzeEmailForPhishing(
        phishingData: PhishingData,
        onDebug: ((String) -> Unit)? = null
    ): Pair<Boolean, String?> {
        if (phishingData.dataType != "Email") {
            onDebug?.invoke("EmailDetector: Warning: analyzeEmailForPhishing called with dataType '${phishingData.dataType}'. Processing content anyway.")
        }

        if (phishingData.content.isBlank()) {
            onDebug?.invoke("EmailDetector: Email content is blank. Cannot analyze.")
            return Pair(false, "Email content is blank")
        }

        onDebug?.invoke("EmailDetector: Analyzing email content for phishing (ID: ${phishingData.metadata["messageId"] ?: "Unknown"})...")
        val (apiResponse, _) = fetchPhishingPredictionFromApi(phishingData.content, onDebug)

        if (apiResponse == null) {
            onDebug?.invoke("EmailDetector: API response was null. Cannot determine phishing status.")
            return Pair(false, "API response was null")
        }

        val (decisionText, explanation) = parseLlmApiResponse(apiResponse, onDebug)

        if (decisionText == null) {
            onDebug?.invoke("EmailDetector: Failed to parse or get a decision text from API response. Explanation: $explanation")
            return Pair(false, explanation ?: "Failed to get a decision from API response")
        }

        // Use normalizeDecision to get a binary prediction
        // Pass a dummy groundTruth as it's not applicable for live prediction.
        val (predictedBinary, _) = normalizeDecision(decisionText, "", onDebug)

        val isPhishing = predictedBinary == 1
        onDebug?.invoke("EmailDetector: Final phishing assessment: $isPhishing. (Text: '$decisionText', Binary: $predictedBinary). Explanation: $explanation")

        return Pair(isPhishing, explanation)
    }
}
