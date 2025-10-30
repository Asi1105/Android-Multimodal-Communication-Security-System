package com.example.anticenter.collectors

import android.content.Context
import android.text.Html // Ensure this import is present
import android.util.Base64
import android.util.Log
import com.example.anticenter.BuildConfig
import com.example.anticenter.data.PhishingData
import com.example.anticenter.data.PhishingDataHub
import com.google.api.services.gmail.model.Message
import com.example.anticenter.analyzers.EmailDetector

class EmailCollector(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val phishingDataHubUnused: PhishingDataHub
) {

    companion object {
        private const val TAG = "EmailCollector"
        private val DIFY_API_KEY = BuildConfig.DIFY_API_KEY
    }

    private fun parseEmailToPhishingData(gmailMessage: Message): PhishingData? {
        try {
            val subject = gmailMessage.payload?.headers?.find { it.name == "Subject" }?.value ?: "No Subject"
            val from = gmailMessage.payload?.headers?.find { it.name == "From" }?.value ?: "Unknown Sender"
            val dateReceived = gmailMessage.payload?.headers?.find { it.name == "Date" }?.value ?: ""
            val snippet = gmailMessage.snippet ?: ""
            var emailBodyContent = ""

            val payload = gmailMessage.payload

            if (payload != null) {
                // Prioritize text/plain from parts if they exist
                var plainTextFromBodyParts: String? = null
                var htmlFromBodyParts: String? = null

                payload.parts?.forEach { part ->
                    if ("text/plain".equals(part.mimeType, ignoreCase = true) && part.body?.data != null) {
                        plainTextFromBodyParts = String(Base64.decode(part.body.data, Base64.URL_SAFE), Charsets.UTF_8)
                        // If we find plain text in parts, we prefer it, so we can break early from part iteration
                        return@forEach
                    } else if ("text/html".equals(part.mimeType, ignoreCase = true) && part.body?.data != null) {
                        htmlFromBodyParts = String(Base64.decode(part.body.data, Base64.URL_SAFE), Charsets.UTF_8)
                    }
                }

                if (plainTextFromBodyParts != null) {
                    emailBodyContent = plainTextFromBodyParts!!
                } else if (htmlFromBodyParts != null) {
                    emailBodyContent = Html.fromHtml(htmlFromBodyParts, Html.FROM_HTML_MODE_LEGACY).toString()
                } else if (payload.body?.data != null) {
                    // No usable parts, or not multipart: process main payload body
                    val mainBodyData = String(Base64.decode(payload.body.data, Base64.URL_SAFE), Charsets.UTF_8)
                    if ("text/plain".equals(payload.mimeType, ignoreCase = true)) {
                        emailBodyContent = mainBodyData
                    } else if ("text/html".equals(payload.mimeType, ignoreCase = true)) {
                        emailBodyContent = Html.fromHtml(mainBodyData, Html.FROM_HTML_MODE_LEGACY).toString()
                    } else {
                        // Fallback for other main body mimeTypes if necessary, or leave empty if unknown
                        // For now, if it's not explicitly text/plain or text/html, we might not want it.
                        // However, if it's the *only* body content, it might be plain text without a proper mime type.
                        // This is a tricky fallback. A safer bet is to only accept explicit text types.
                        // For now, let's log and assign, but be aware.
                        if (payload.mimeType?.startsWith("text/") == true) {
                           emailBodyContent = mainBodyData // e.g. text/calendar, could be noisy
                        }
                        Log.w(TAG, "Main payload mimeType is '${payload.mimeType}'. Content might not be plain text.")
                    }
                }
            }

            // Fallback to snippet if body content is still empty
            if (emailBodyContent.isEmpty() && snippet.isNotEmpty()) {
                emailBodyContent = snippet
                Log.i(TAG, "Email body was empty after parsing, using snippet as content for Subject: $subject")
            }

            if (emailBodyContent.isBlank()) {
                Log.i(TAG, "Email from: $from, Subject: $subject has blank content after all parsing attempts. Not storing.")
                return null
            }

            Log.i(TAG, "Successfully parsed email to plain text. Subject: $subject")

            return PhishingData(
                dataType = "Email",
                content = emailBodyContent.trim(), // Trim the final content
                metadata = mapOf(
                    "subject" to subject,
                    "sender" to from,
                    "snippet" to snippet,
                    "dateReceived" to dateReceived,
                    "messageId" to (gmailMessage.id ?: "N/A"),
                    "threadId" to (gmailMessage.threadId ?: "N/A"),
                    "timestamp" to System.currentTimeMillis().toString()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing email content for message ID: ${gmailMessage.id ?: "Unknown ID"}", e)
            return null
        }
    }

    private suspend fun addPhishingDataToHub(data: PhishingData) {
        PhishingDataHub.addData(data)
        Log.i(TAG, "PhishingData for email (Subject: ${data.metadata["subject"]}) added to PhishingDataHub.")
    }

    suspend fun processRetrievedEmail(gmailMessage: Message) {
        try {
            Log.d(TAG, "Starting to process email with ID: ${gmailMessage.id ?: "Unknown ID"}")
            val parsedData = parseEmailToPhishingData(gmailMessage)

            if (parsedData != null) {
                Log.i(TAG, "Email (Subject: ${parsedData.metadata["subject"]}) parsed. Proceeding to phishing analysis.")

                val apiKey = DIFY_API_KEY
                if (apiKey == "YOUR_API_KEY_HERE" || apiKey.isBlank()) { // Ensure you've replaced this
                    Log.e(TAG, "API Key is not set or is invalid in EmailCollector.kt. Please replace 'YOUR_API_KEY_HERE'. Email (Subject: ${parsedData.metadata["subject"]}) will not be analyzed or stored.")
                    return
                }

                val emailDetector = EmailDetector(apiKey)
                val (isPhishing, explanation) = emailDetector.analyzeEmailForPhishing(parsedData) { debugMessage ->
                    Log.d(TAG, "[EmailDetector] $debugMessage")
                }

                if (isPhishing) {
                    Log.i(TAG, "Phishing email DETECTED by EmailDetector. Storing it. Subject: ${parsedData.metadata["subject"]}")
                    val phishingEmailData = parsedData.copy(
                        metadata = parsedData.metadata + mapOf(
                            "llmDecision" to "phishing",
                            "llmExplanation" to (explanation ?: "N/A")
                        )
                    )
                    addPhishingDataToHub(phishingEmailData)
                } else {
                    Log.i(TAG, "Email (Subject: ${parsedData.metadata["subject"]}) NOT classified as phishing by EmailDetector. Explanation: $explanation. Not storing.")
                }

            } else {
                Log.w(TAG, "Email parsing failed or content was blank. Not storing. Message ID: ${gmailMessage.id ?: "Unknown ID"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Outer error processing email: ${gmailMessage.id ?: "Unknown ID"}", e)
        }
    }
}
