package com.example.anticenter.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CybertraceRiskClientTest {

    private val client = CybertraceRiskClient()

    private fun parse(html: String): CybertraceRiskClient.Result? {
        val method = CybertraceRiskClient::class.java.getDeclaredMethod("parseHtml", String::class.java)
        method.isAccessible = true
        return method.invoke(client, html) as CybertraceRiskClient.Result?
    }

    @Test
    fun parseHighRiskHtml_returnsRichMetadata() {
        val html = """
            <div class="risk">
                <h2>Risk Score : 82%</h2>
                <p>This phone number is considered high risk.</p>
                <p>Searched 128 times</p>
                <p>Reported 12 times</p>
            </div>
        """.trimIndent()

        val result = parse(html)

        requireNotNull(result)
        assertEquals(82, result.riskPercent)
        assertEquals("high risk", result.riskStatement)
        assertEquals(128, result.searchedCount)
        assertEquals(12, result.reportedCount)
        assertEquals(CybertraceRiskClient.RiskLevel.HIGH, result.riskLevel)
        assertTrue(result.rawSnippet.contains("Risk Score"))
    }

    @Test
    fun parseMediumRiskHtml_handlesLowerCounts() {
        val html = """
            <section>
                <span>Phone Number Details</span>
                <div>Risk Score : 45%</div>
                <p>This phone number is considered medium risk based on recent activity.</p>
                <p>Searched 5 times</p>
            </section>
        """.trimIndent()

        val result = parse(html)

        requireNotNull(result)
        assertEquals(45, result.riskPercent)
        assertEquals("medium risk based on recent activity", result.riskStatement)
        assertEquals(5, result.searchedCount)
        assertNull(result.reportedCount)
        assertEquals(CybertraceRiskClient.RiskLevel.MEDIUM, result.riskLevel)
    }

    @Test
    fun parseHtml_withNoSignals_returnsUnknownRisk() {
        val html = """
            <html>
                <body>
                    <p>No data available for this number yet.</p>
                </body>
            </html>
        """.trimIndent()

        val result = parse(html)

        requireNotNull(result)
        assertNull(result.riskPercent)
        assertNull(result.riskStatement)
        assertNull(result.searchedCount)
        assertNull(result.reportedCount)
        assertEquals(CybertraceRiskClient.RiskLevel.UNKNOWN, result.riskLevel)
    }

    @Test
    fun riskLevel_boundariesBehaveAsExpected() {
        val high = CybertraceRiskClient.Result(75, null, null, null, "snippet")
        val medium = CybertraceRiskClient.Result(35, null, null, null, "snippet")
        val low = CybertraceRiskClient.Result(12, null, null, null, "snippet")
        val unknown = CybertraceRiskClient.Result(null, null, null, null, "snippet")

        assertEquals(CybertraceRiskClient.RiskLevel.HIGH, high.riskLevel)
        assertEquals(CybertraceRiskClient.RiskLevel.MEDIUM, medium.riskLevel)
        assertEquals(CybertraceRiskClient.RiskLevel.LOW, low.riskLevel)
        assertEquals(CybertraceRiskClient.RiskLevel.UNKNOWN, unknown.riskLevel)
    }
}
