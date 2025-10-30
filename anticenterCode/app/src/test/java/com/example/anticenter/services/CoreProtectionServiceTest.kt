package com.example.anticenter.services

import com.example.anticenter.SelectFeatures
import com.example.anticenter.data.PhishingData
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * PhishingDataConverter 单元测试
 * 测试所有数据转换分支和边界情况
 * 
 * 这些测试覆盖了 CoreProtectionService 中的关键业务逻辑
 */
class PhishingDataConverterTest {

    // ==================== 数据类型映射测试 ====================

    @Test
    fun `mapDataTypeToFeature should return emailProtection for email`() {
        // When
        val result = PhishingDataConverter.mapDataTypeToFeature("email")

        // Then
        assertThat(result).isEqualTo(SelectFeatures.emailProtection)
    }

    @Test
    fun `mapDataTypeToFeature should return meetingProtection for zoom`() {
        val result = PhishingDataConverter.mapDataTypeToFeature("zoom")
        assertThat(result).isEqualTo(SelectFeatures.meetingProtection)
    }

    @Test
    fun `mapDataTypeToFeature should return callProtection for phonecall`() {
        val result = PhishingDataConverter.mapDataTypeToFeature("phonecall")
        assertThat(result).isEqualTo(SelectFeatures.callProtection)
    }

    @Test
    fun `mapDataTypeToFeature should return callProtection for unknown type`() {
        // 测试 else 分支 - 默认值
        val result = PhishingDataConverter.mapDataTypeToFeature("unknown")
        assertThat(result).isEqualTo(SelectFeatures.callProtection)
    }

    @Test
    fun `mapDataTypeToFeature should be case insensitive`() {
        // 测试大小写不敏感
        val result1 = PhishingDataConverter.mapDataTypeToFeature("EMAIL")
        val result2 = PhishingDataConverter.mapDataTypeToFeature("Email")
        val result3 = PhishingDataConverter.mapDataTypeToFeature("email")

        assertThat(result1).isEqualTo(SelectFeatures.emailProtection)
        assertThat(result2).isEqualTo(SelectFeatures.emailProtection)
        assertThat(result3).isEqualTo(SelectFeatures.emailProtection)
    }

    // ==================== PhishingData 转换测试 ====================

    @Test
    fun `convertToAlertItem should convert email data correctly`() {
        // Given: Email 类型的 PhishingData
        val phishingData = PhishingData(
            dataType = "email",
            content = "Phishing email content",
            metadata = mapOf(
                "llmDecision" to "phishing",
                "sender" to "scammer@fake.com",
                "subject" to "Urgent: Verify Your Account"
            )
        )

        // When: 转换为 AlertLogItem
        val result = PhishingDataConverter.convertToAlertItem(phishingData)

        // Then: 验证转换结果
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo("Phishing Email")
        assertThat(result.source).contains("scammer@fake.com")
        assertThat(result.source).contains("Urgent: Verify Your Account")
        assertThat(result.status).isEqualTo("Detected")
    }

    @Test
    fun `convertToAlertItem should handle zoom video deepfake`() {
        // Given: Zoom Video Deepfake
        val phishingData = PhishingData(
            dataType = "zoom",
            content = "Suspicious meeting",
            metadata = mapOf(
                "mediaType" to "VIDEO",
                "detector" to "RealityDefender",
                "fileName" to "meeting_2025.mp4"
            )
        )

        // When
        val result = PhishingDataConverter.convertToAlertItem(phishingData)

        // Then
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo("Suspicious Meeting")
        assertThat(result.source).contains("Deepfake Video")
        assertThat(result.source).contains("meeting_2025.mp4")
    }

    @Test
    fun `convertToAlertItem should handle zoom audio phishing`() {
        // Given: Zoom Audio Phishing
        val phishingData = PhishingData(
            dataType = "zoom",
            content = "Voice phishing detected",
            metadata = mapOf(
                "mediaType" to "AUDIO_PHISHING",
                "detector" to "DifyVoiceDetector",
                "fileName" to "call_audio.wav"
            )
        )

        // When
        val result = PhishingDataConverter.convertToAlertItem(phishingData)

        // Then
        assertThat(result).isNotNull()
        assertThat(result!!.source).contains("Voice Phishing")
        assertThat(result.source).contains("call_audio.wav")
    }

    @Test
    fun `convertToAlertItem should handle zoom audio deepfake`() {
        // Given: Zoom Audio Deepfake
        val phishingData = PhishingData(
            dataType = "zoom",
            content = "Audio deepfake",
            metadata = mapOf(
                "mediaType" to "AUDIO",
                "fileName" to "meeting_audio.wav"
            )
        )

        // When
        val result = PhishingDataConverter.convertToAlertItem(phishingData)

        // Then
        assertThat(result).isNotNull()
        assertThat(result!!.source).contains("Deepfake Audio")
    }

    @Test
    fun `convertToAlertItem should handle zoom image deepfake`() {
        // Given: Zoom Image Deepfake
        val phishingData = PhishingData(
            dataType = "zoom",
            content = "Image deepfake",
            metadata = mapOf(
                "mediaType" to "IMAGE",
                "fileName" to "screenshot.png"
            )
        )

        // When
        val result = PhishingDataConverter.convertToAlertItem(phishingData)

        // Then
        assertThat(result).isNotNull()
        assertThat(result!!.source).contains("Deepfake Image")
    }

    @Test
    fun `convertToAlertItem should handle zoom unknown media type`() {
        // Given: Unknown media type
        val phishingData = PhishingData(
            dataType = "zoom",
            content = "Unknown media",
            metadata = mapOf(
                "mediaType" to "UNKNOWN",
                "fileName" to "file.xyz"
            )
        )

        // When
        val result = PhishingDataConverter.convertToAlertItem(phishingData)

        // Then: 应该使用默认值
        assertThat(result).isNotNull()
        assertThat(result!!.source).contains("Unknown Threat")
    }

    @Test
    fun `convertToAlertItem should handle phonecall data`() {
        // Given: Phone call data
        val phishingData = PhishingData(
            dataType = "phonecall",
            content = "Suspicious call",
            metadata = mapOf(
                "source" to "+8613800138000"
            )
        )

        // When
        val result = PhishingDataConverter.convertToAlertItem(phishingData)

        // Then
        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo("Suspicious Call")
        assertThat(result.source).isEqualTo("+8613800138000")
    }

    @Test
    fun `convertToAlertItem should return null for unknown type`() {
        // Given: Unknown data type - 测试 else 分支
        val phishingData = PhishingData(
            dataType = "unknown_type",
            content = "Unknown",
            metadata = emptyMap()
        )

        // When
        val result = PhishingDataConverter.convertToAlertItem(phishingData)

        // Then: 应该返回 null
        assertThat(result).isNull()
    }

    @Test
    fun `convertToAlertItem should handle missing metadata gracefully`() {
        // Given: Email without metadata
        val phishingData = PhishingData(
            dataType = "email",
            content = "Email",
            metadata = emptyMap()  // 缺少 sender 和 subject
        )

        // When
        val result = PhishingDataConverter.convertToAlertItem(phishingData)

        // Then: 应该使用默认值
        assertThat(result).isNotNull()
        assertThat(result!!.source).contains("Unknown Sender")
        assertThat(result.source).contains("No Subject")
    }

    @Test
    fun `convertToAlertItem should distinguish phishing from suspicious email`() {
        // Given: llmDecision = "phishing"
        val phishingEmail = PhishingData(
            dataType = "email",
            content = "Content",
            metadata = mapOf("llmDecision" to "phishing", "sender" to "test@test.com", "subject" to "Test")
        )

        // When
        val result1 = PhishingDataConverter.convertToAlertItem(phishingEmail)

        // Then
        assertThat(result1!!.type).isEqualTo("Phishing Email")

        // Given: llmDecision != "phishing"
        val suspiciousEmail = PhishingData(
            dataType = "email",
            content = "Content",
            metadata = mapOf("llmDecision" to "suspicious", "sender" to "test@test.com", "subject" to "Test")
        )

        // When
        val result2 = PhishingDataConverter.convertToAlertItem(suspiciousEmail)

        // Then
        assertThat(result2!!.type).isEqualTo("Suspicious Email")
    }
}
