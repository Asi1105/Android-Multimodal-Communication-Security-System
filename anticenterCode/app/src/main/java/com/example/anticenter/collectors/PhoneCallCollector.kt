package com.example.anticenter.collectors

import android.content.Context
import android.util.Log
import com.example.anticenter.data.PhishingData
import com.example.anticenter.data.PhishingDataHub
import com.example.anticenter.services.BCRMonitorCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 电话录音采集器 - 与 BCR App 联动
 * 
 * 工作原理：
 * - start(): 启动 BCRMonitorCollector 监听 BCR App 的录音文件
 * - BCR App 在通话时自动录音
 * - BCRMonitorCollector 每 10 秒实时检测录音文件并进行钓鱼分析
 * 
 * 优势：
 * - ✅ 利用 BCR App 的录音，无需重复录音
 * - ✅ 资源占用低
 * - ✅ 每 10 秒实时检测（vs 旧方案的 20 秒）
 * - ✅ 使用 FFmpeg 专业音频处理
 */
class PhoneCallCollector(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") private val dataHub: PhishingDataHub
) : Collector {

    companion object { 
        private const val TAG = "PhoneCallCollector"
        
        // 单例 BCRMonitorCollector 实例
        @Volatile
        private var bcrMonitor: BCRMonitorCollector? = null
        
        fun getOrCreateBCRMonitor(context: Context): BCRMonitorCollector {
            return bcrMonitor ?: synchronized(this) {
                bcrMonitor ?: BCRMonitorCollector(context.applicationContext).also {
                    bcrMonitor = it
                    Log.d(TAG, "BCRMonitorCollector 实例已创建")
                }
            }
        }
    }

    override val id: CollectorId = CollectorId.PHONE_CALL
    private val _state = MutableStateFlow(CollectorState.IDLE)
    override val state: StateFlow<CollectorState> = _state
    
    // 协程作用域用于管理 BCRMonitor 的生命周期
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun startCollection(): Boolean = start()

    fun stopCollection() {
        try {
            val monitor = bcrMonitor
            if (monitor != null) {
                collectorScope.launch {
                    monitor.stopCollection()
                    Log.i(TAG, "BCRMonitorCollector 已停止")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "停止 BCRMonitor 失败", e)
        }
        _state.value = CollectorState.IDLE
        Log.i(TAG, "通话录音采集器已禁用")
    }

    override suspend fun start(): Boolean {
        if (_state.value == CollectorState.RUNNING || _state.value == CollectorState.STARTING) {
            Log.d(TAG, "BCRMonitor 已在运行中，跳过启动")
            return true
        }
        
        return try {
            _state.value = CollectorState.STARTING
            
            // 获取或创建 BCRMonitorCollector 实例
            val monitor = getOrCreateBCRMonitor(context)
            
            // 在协程中启动 BCRMonitor
            collectorScope.launch {
                try {
                    monitor.startCollection()
                    Log.i(TAG, "✅ BCRMonitorCollector 已启动，开始监听 BCR App 录音")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 启动 BCRMonitor 失败", e)
                    _state.value = CollectorState.ERROR
                }
            }
            
            _state.value = CollectorState.RUNNING
            Log.i(TAG, "📞 PhoneCallCollector 启动成功（BCR 联动模式）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "❌ 启动通话录音采集器失败", t)
            _state.value = CollectorState.ERROR
            false
        }
    }

    override suspend fun stop() { stopCollection() }

    override fun isRunning(): Boolean = _state.value == CollectorState.RUNNING
}
