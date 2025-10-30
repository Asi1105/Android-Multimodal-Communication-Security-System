package com.example.anticenter.data

data class AllowlistItem(
    val id: String = "",
    val name: String = "",
    val type: String = "Phone", // Phone, Email, URL
    val value: String = "",
    val description: String = ""
)
