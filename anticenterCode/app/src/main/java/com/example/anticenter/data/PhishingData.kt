package com.example.anticenter.data

// 用于封装从不同来源收集到的数据
data class PhishingData(
    val dataType: String,  // 数据来源类型（如：Zoom，Email，PhoneCall）
    val content: String,   // 数据核心内容（如：邮件正文、Zoom 会议内容等）
    val metadata: Map<String, String>  // 附加的元数据（如：发件人，会议ID等）
)

// PhishingData 中的 Email 数据类
data class Email(
    val id: String,
    val subject: String,
    val snippet: String,
    val extra: Map<String, String>? = null
)
