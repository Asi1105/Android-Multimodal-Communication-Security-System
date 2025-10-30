package com.example.anticenter.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * PhishingDataHub: 中央数据存储和分发中心
 *
 * 使用 Kotlin Flows 来管理和分发所有收集到的钓鱼数据。
 * 这是单例模式，确保应用中只有一个数据中心实例。
 */
object PhishingDataHub {
    // 使用一个共享流来接收所有类型的钓鱼数据。
    // 将数据类型改为 Any，使其能够接受所有对象。
    private val _phishingDataFlow = MutableSharedFlow<Any>(replay = 1)
    val phishingDataFlow = _phishingDataFlow.asSharedFlow()

    /**
     * 接收并处理来自数据收集器的数据。
     *
     * @param data 从数据收集器（如 EmailCollector）传入的数据对象。
     */
    suspend fun addData(data: Any) {
        _phishingDataFlow.emit(data)
    }
}