package com.example.anticenter.collectors

import kotlinx.coroutines.flow.StateFlow

// 定义采集器类型（Zoom、Gmail、PhoneCall）
enum class CollectorId { ZOOM, GMAIL, PHONE_CALL }

// 定义采集器状态
enum class CollectorState { IDLE, STARTING, RUNNING, STOPPING, ERROR }

// 采集器接口：实现方需要维护状态并提供启停方法
interface Collector {
    val id: CollectorId
    val state: StateFlow<CollectorState>
    suspend fun start(): Boolean
    suspend fun stop()
    fun isRunning(): Boolean
}
