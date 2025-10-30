package com.example.anticenter.services

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lightweight scraper for Cybertrace Scam Phone Number Lookup risk metadata.
 *
 * The public page renders the risk score server side, so we can issue a GET request against
 * https://www.cybertrace.com.au/scam-phone-number-lookup/?search=<digits> and parse the HTML snippet.
 * This helper keeps the parsing logic encapsulated and resilient to minor markup changes.
 */
class CybertraceRiskClient(
    private val httpClient: OkHttpClient = defaultHttpClient
) {

    data class Result(
        val riskPercent: Int?,
        val riskStatement: String?,
        val searchedCount: Int?,
        val reportedCount: Int?,
        val rawSnippet: String
    ) {
        val riskLevel: RiskLevel
            get() = when {
                riskPercent == null -> RiskLevel.UNKNOWN
                riskPercent >= 60 -> RiskLevel.HIGH
                riskPercent >= 30 -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            }
    }

    enum class RiskLevel { LOW, MEDIUM, HIGH, UNKNOWN }

    @Throws(IOException::class)
    fun lookup(phoneNumber: String): Result? {
        val digitsOnly = phoneNumber.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) return null

        val url = "$BASE_URL?search=$digitsOnly"
        val request = Request.Builder()
            .url(url)
            .get()
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; AntiCenter) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.8")
            .header("Referer", "https://www.cybertrace.com.au/")
            .build()

        val call = httpClient.newCall(request)
        call.timeout().timeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        call.execute().use { response ->
            if (!response.isSuccessful) {
                android.util.Log.w(TAG, "[CYBERTRACE] Unexpected HTTP ${'$'}{response.code} for ${'$'}url")
                return null
            }
            val body = response.body ?: return null
            val html = body.string()
            return parseHtml(html)
        }
    }

    private fun parseHtml(html: String): Result? {
        val riskPercent = RISK_PERCENT.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val riskStatement = RISK_STATEMENT.find(html)?.groupValues?.getOrNull(1)?.trim()?.let {
            it.replace("\n", " ").replace("  ", " ")
        }
        val searchedCount = SEARCHED_COUNT.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val reportedCount = REPORTED_COUNT.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val snippetBuilder = StringBuilder()
        val lines = html.lines()
        for (line in lines) {
            if (line.contains("Risk Score", ignoreCase = true) ||
                line.contains("Phone Number Details", ignoreCase = true) ||
                line.contains("Searched", ignoreCase = true)
            ) {
                snippetBuilder.append(line.trim()).append('\n')
            }
        }

        val snippet = snippetBuilder.toString().ifBlank { html.take(600) }
        if (riskPercent == null && riskStatement == null && searchedCount == null && reportedCount == null) {
            android.util.Log.w(TAG, "[CYBERTRACE] Could not extract risk metadata from response snippet=${'$'}snippet")
        }
        return Result(riskPercent, riskStatement, searchedCount, reportedCount, snippet)
    }

    companion object {
        private const val TAG = "CybertraceRiskClient"
        private const val BASE_URL = "https://www.cybertrace.com.au/scam-phone-number-lookup/"
    private const val CALL_TIMEOUT_MS = 4_000L

    private val RISK_PERCENT = Regex("Risk\\s*Score\\s*:\\s*(\\d{1,3})%", RegexOption.IGNORE_CASE)
        private val RISK_STATEMENT = Regex("This phone number is considered\\s+([^.<]+)", RegexOption.IGNORE_CASE)
        private val SEARCHED_COUNT = Regex("Searched\\s+(\\d+)\\s+times", RegexOption.IGNORE_CASE)
        private val REPORTED_COUNT = Regex("Reported\\s+(\\d+)\\s+times", RegexOption.IGNORE_CASE)

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(7, TimeUnit.SECONDS)
                .writeTimeout(7, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
