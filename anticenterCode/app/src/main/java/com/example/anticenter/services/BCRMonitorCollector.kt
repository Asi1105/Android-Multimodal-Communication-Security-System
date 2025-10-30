package com.example.anticenter.services

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.FileObserver
import android.util.Log
import com.example.anticenter.analyzers.FileUploadManager
import kotlinx.coroutines.*
import java.io.File
import android.net.Uri
import com.example.anticenter.analyzers.FileTestResult
import com.example.anticenter.data.PhishingData
import com.example.anticenter.data.PhishingDataHub
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BCRMonitorCollector(private val context: Context) {

    companion object {
        private const val TAG = "BCRMonitorCollector"

        // BCR 录音路径（优先私有目录，需要 Root）
        private const val BCR_PRIVATE_PATH =
            "/storage/emulated/0/Android/data/com.chiller3.bcr/files"
        private const val SNAPSHOT_INTERVAL_MS = 10000L // 10秒快照间隔
        private const val AUDIO_CHUNK_DURATION_SECONDS = 10 // 保留最新10秒音频
        private const val DIFY_API_KEY = "app-j0J1Qt5SLk305PwR61Djy5fn"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fileObserver: FileObserver? = null
    private var monitorJob: Job? = null
    private lateinit var fileUploadManager: FileUploadManager

    // 当前正在录音的文件路径
    private var currentRecordingFile: String? = null
    private var snapshotCount = 0
    private var lastFileSize = 0L

    /**
     * 开始监控BCR录音
     * 
     * 策略（学习 Zoom 成功经验）：
     * 1. 使用 Root 访问 BCR 私有目录
     * 2. 定位正在写入的录音文件
     * 3. 定期用 Root 复制快照到 App 自己的 cacheDir
     * 4. 在本地使用 MediaCodec 等 Java API 处理（避免 SELinux 限制）
     */
    suspend fun startCollection() {
        withContext(Dispatchers.IO) {
            try {
                fileUploadManager = FileUploadManager(context, DIFY_API_KEY)

                // 验证 Root 权限并找到 BCR 录音目录
                val recordingsPath = verifyBCRAccessWithRoot()
                if (recordingsPath == null) {
                    throw Exception("无法访问 BCR 录音目录，需要 Root 权限")
                }

                Log.i(TAG, "✅ BCR directory accessible via Root: $recordingsPath")

                // 创建本地缓存目录（用于存放复制的快照）
                val snapshotDir = File(context.cacheDir, "bcr_snapshots").apply { 
                    mkdirs() 
                    // 清理旧快照
                    listFiles()?.forEach { it.delete() }
                }
                Log.d(TAG, "📂 Local snapshot directory: ${snapshotDir.absolutePath}")

                // 启动监控任务（定期检查并复制快照）
                startMonitoringLoop(recordingsPath, snapshotDir)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting BCR monitoring", e)
                throw e
            }
        }
    }

    /**
     * 验证 Root 权限并找到 BCR 录音目录
     * 
     * @return BCR 目录的绝对路径，失败返回 null
     */
    private fun verifyBCRAccessWithRoot(): String? {
        val path = BCR_PRIVATE_PATH
        
        try {
            Log.i(TAG, "🔍 Checking BCR directory with Root: $path")
            
            // 1. 检查目录是否存在
            val testCmd = "test -d \"$path\" && echo 'exists' || echo 'notfound'"
            val testProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", testCmd))
            val testResult = testProcess.inputStream.bufferedReader().readText().trim()
            testProcess.waitFor()

            if (testResult != "exists") {
                Log.e(TAG, "❌ BCR directory does not exist: $path")
                Log.e(TAG, "   Please install BCR and make at least one recording")
                return null
            }

            Log.i(TAG, "✅ BCR directory exists")

            // 2. 修改权限（尝试让普通访问也能工作，但主要还是用 Root）
            val chmodCmd = "chmod -R 755 \"$path\""
            Runtime.getRuntime().exec(arrayOf("su", "-c", chmodCmd)).waitFor()
            Log.d(TAG, "🔧 Changed permissions to 755")

            // 3. 验证可以列出文件
            val lsCmd = "ls -1 \"$path\" 2>/dev/null | head -3"
            val lsProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", lsCmd))
            val lsOutput = lsProcess.inputStream.bufferedReader().readText()
            lsProcess.waitFor()

            Log.i(TAG, "✅ Root access verified (can list directory)")
            if (lsOutput.isNotBlank()) {
                Log.d(TAG, "📂 Sample files:\n${lsOutput.trim()}")
            }

            return path

        } catch (e: Exception) {
            Log.e(TAG, "❌ Root access failed", e)
            Log.e(TAG, "   Make sure device is rooted and grant Root permission")
            return null
        }
    }

    /**
     * 启动监控循环（类似 Zoom 的 AudioWatcher）
     * 
     * 每隔一定时间：
     * 1. 用 Root 找到正在录音的文件
     * 2. 用 Root 复制快照到本地目录
     * 3. 在本地处理快照（MediaCodec + 上传）
     */
    private fun startMonitoringLoop(bcrPath: String, snapshotDir: File) {
        Log.i(TAG, "🎧 Starting monitoring loop (check interval: ${SNAPSHOT_INTERVAL_MS}ms)")

        monitorJob = scope.launch {
            while (isActive) {
                try {
                    // 1. 用 Root 找到正在录音的文件
                    val activeFile = findActiveRecordingWithRoot(bcrPath)
                    
                    if (activeFile != null) {
                        Log.d(TAG, "🎵 Active recording: ${activeFile.name}")
                        
                        // 2. 用 Root 复制快照到本地
                        val snapshot = copySnapshotToLocal(activeFile, snapshotDir)
                        
                        if (snapshot != null && snapshot.exists()) {
                            // 3. 在本地处理快照（Java API 可用！）
                            processLocalSnapshot(snapshot)
                        }
                    } else {
                        Log.d(TAG, "📭 No active recording detected")
                    }

                    // 等待下一次检查
                    delay(SNAPSHOT_INTERVAL_MS)

                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "⚠️ Error in monitoring loop", e)
                    delay(5000) // 出错后等待 5 秒再重试
                }
            }
        }
    }

    /**
     * 文件信息数据类
     */
    private data class FileInfo(
        val name: String,
        val path: String,
        val size: Long,
        val modTime: Long
    )

    /**
     * 使用 Root 找到正在录音的文件
     * 
     * 策略：找到最近 60 秒内修改的音频文件（正在写入中）
     * 
     * @return 正在录音的文件信息，没有则返回 null
     */
    private fun findActiveRecordingWithRoot(bcrPath: String): FileInfo? {
        try {
            val now = System.currentTimeMillis()
            
            // 1. 列出所有音频文件及其修改时间（使用 find 命令）
            // find 命令：查找最近 2 分钟内修改的 .oga/.ogg/.m4a 文件
            val findCmd = """
                find "$bcrPath" -maxdepth 1 -type f \
                    \( -name "*.oga" -o -name "*.ogg" -o -name "*.m4a" -o -name "*.opus" \) \
                    -mmin -2 \
                    -exec stat -c '%n|%s|%Y' {} \; 2>/dev/null
            """.trimIndent()
            
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", findCmd))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (output.isBlank()) {
                return null
            }

            // 2. 解析输出，找到最近修改的文件
            val files = output.trim().split("\n")
                .mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size == 3) {
                        val path = parts[0]
                        val size = parts[1].toLongOrNull() ?: 0L
                        val modTimeSec = parts[2].toLongOrNull() ?: 0L
                        val modTimeMs = modTimeSec * 1000
                        
                        // 只要最近 60 秒内修改的
                        if (now - modTimeMs < 60_000) {
                            FileInfo(
                                name = File(path).name,
                                path = path,
                                size = size,
                                modTime = modTimeMs
                            )
                        } else null
                    } else null
                }
                .sortedByDescending { it.modTime }

            // 3. 返回最新的文件
            val activeFile = files.firstOrNull()
            
            if (activeFile != null) {
                val ageSeconds = (now - activeFile.modTime) / 1000.0
                Log.d(TAG, "   📄 ${activeFile.name}")
                Log.d(TAG, "   📊 Size: ${activeFile.size} bytes")
                Log.d(TAG, "   🕒 Modified: ${ageSeconds}s ago")
                
                // 避免重复处理同一个文件（检查是否是同一个）
                if (currentRecordingFile != null && 
                    currentRecordingFile == activeFile.path) {
                    // 同一个文件，检查大小是否增长
                    if (activeFile.size > lastFileSize) {
                        Log.d(TAG, "   📈 File growing: ${lastFileSize} → ${activeFile.size} bytes")
                        lastFileSize = activeFile.size
                    } else {
                        Log.d(TAG, "   ⏸️ File size unchanged, might be finished")
                        return null
                    }
                } else {
                    // 新文件
                    currentRecordingFile = activeFile.path
                    lastFileSize = activeFile.size
                    snapshotCount = 0
                    Log.i(TAG, "✅ New active recording detected: ${activeFile.name}")
                }
            }

            return activeFile

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error finding active recording", e)
            return null
        }
    }

    /**
     * 使用 Root 复制文件快照到本地目录
     * 
     * 学习 Zoom 的策略：cp + chmod，确保文件可访问
     * 
     * @param fileInfo BCR 文件信息
     * @param destDir 本地目标目录
     * @return 本地快照文件，失败返回 null
     */
    private fun copySnapshotToLocal(fileInfo: FileInfo, destDir: File): File? {
        try {
            snapshotCount++
            val timestamp = System.currentTimeMillis()
            val destFile = File(destDir, "snapshot_${timestamp}_${snapshotCount}.${fileInfo.name.substringAfterLast(".")}")

            Log.d(TAG, "📋 Copying snapshot #$snapshotCount...")
            Log.d(TAG, "   From: ${fileInfo.path}")
            Log.d(TAG, "   To: ${destFile.absolutePath}")

            // 使用 Root 复制文件并设置权限（学习 Zoom）
            val cpCmd = """
                cp "${fileInfo.path}" "${destFile.absolutePath}" && \
                chmod 666 "${destFile.absolutePath}"
            """.trimIndent()
            
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cpCmd))
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                Log.e(TAG, "❌ Copy failed with exit code $exitCode: $error")
                return null
            }

            // 验证文件是否成功复制
            if (destFile.exists() && destFile.length() > 0) {
                Log.i(TAG, "✅ Snapshot copied: ${destFile.length()} bytes")
                return destFile
            } else {
                Log.e(TAG, "❌ Copied file does not exist or is empty")
                return null
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error copying snapshot", e)
            return null
        }
    }

    /**
     * 处理本地快照文件
     * 
     * 现在文件在我们自己的 cacheDir，可以直接使用 Java API！
     * 
     * @param snapshot 本地快照文件
     */
    private suspend fun processLocalSnapshot(snapshot: File) {
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "🎬 Processing local snapshot: ${snapshot.name}")

                // ✅ 现在可以直接使用 MediaCodec/MediaExtractor 了！
                // 文件在我们自己的目录，不受 SELinux 限制
                val wavFile = extractLast10SecondsAsWav(snapshot, snapshot.extension)

                if (wavFile != null && wavFile.exists()) {
                    Log.i(TAG, "✅ WAV extracted: ${wavFile.length()} bytes")

                    // 上传到 Dify API 进行检测
                    uploadAndAnalyzeSnapshot(wavFile)

                    // 清理临时文件
                    wavFile.delete()
                } else {
                    Log.w(TAG, "⚠️ Failed to extract WAV from snapshot")
                }

                // 清理快照文件
                snapshot.delete()
                Log.d(TAG, "🗑️ Snapshot deleted: ${snapshot.name}")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing snapshot", e)
            }
        }
    }

    /**
     * 上传并分析音频快照
     */
    private suspend fun uploadAndAnalyzeSnapshot(wavFile: File) {
        try {
            Log.d(TAG, "📤 Uploading and analyzing: ${wavFile.name}")

            // 创建 URI
            val uri = Uri.fromFile(wavFile)

            // 使用 FileUploadManager 上传并检测
            val result = fileUploadManager.uploadAndTestFile(
                uri = uri,
                fileName = wavFile.name,
                groundTruth = "Unknown"
            ) { progress ->
                Log.d(TAG, "📊 Upload progress: $progress")
            }

            if (result != null) {
                Log.i(TAG, "🔍 Analysis result for snapshot #$snapshotCount:")
                Log.i(TAG, "   Decision: ${result.llmDecision}")
                Log.i(TAG, "   Confidence: ${result.confidence}")

                // 如果检测到钓鱼，发送警报
                if (result.llmDecision == "PHISHING" && result.confidence > 0.7) {
                    sendPhishingAlert(result, snapshotCount)
                }
            } else {
                Log.w(TAG, "⚠️ No analysis result returned")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error uploading and analyzing snapshot", e)
        }
    }

    /**
     * 检测文件是否正在被其他进程写入
     *
     * 原理：
     * 1. 尝试以追加模式打开文件（append mode）
     * 2. 尝试获取独占文件锁（exclusive lock）
     * 3. 如果无法获取锁，说明文件正在被写入
     * 4. 如果能获取锁，说明文件已完成
     *
     * Root 权限：
     * - 在 root 设备上可以访问其他应用的文件
     * - BCR App 正在写入时会持有文件锁
     * - 我们可以检测到这个锁
     *
     * @return true 如果文件正在被写入，false 如果文件已完成
     */
    private fun isFileBeingWritten(file: File): Boolean {
        if (!file.exists() || !file.canRead()) {
            return false
        }

        return try {
            // 方法1: 尝试获取独占写入锁
            java.io.RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                val lock = channel.tryLock(0, Long.MAX_VALUE, true) // 共享锁

                if (lock != null) {
                    lock.release()
                    // 能获取共享锁，但需要进一步检测
                    // 检查文件最近是否有修改（30秒内）
                    val fileAge = (System.currentTimeMillis() - file.lastModified()) / 1000.0
                    return fileAge < 30 // 30秒内修改过，认为可能在写入
                } else {
                    // 无法获取锁，可能正在被写入
                    return true
                }
            }
        } catch (e: java.io.IOException) {
            // IOException 通常表示文件被占用
            Log.d(TAG, "   🔒 File locked (IOException): ${e.message}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "   ⚠️ Lock test error: ${e.message}")
            // 出错时使用时间戳作为后备判断
            val fileAge = (System.currentTimeMillis() - file.lastModified()) / 1000.0
            fileAge < 30
        }
    }

    /**
     * 查找 BCR 录音目录
     *
     * BCR App 默认保存在私有目录：
     * /storage/emulated/0/Android/data/com.chiller3.bcr/files
     *
     * Android 11+ 限制：普通应用无法访问其他应用的 Android/data 目录
     * 解决方案：使用 Root 权限 (su) 修改目录权限
     *
     * @return BCR 录音目录，如果找不到则返回 null
     */
    private fun findBCRRecordingsPath(): File? {
        // BCR 标准私有目录（优先检查）
        val privatePath = BCR_PRIVATE_PATH

        Log.i(TAG, "🔍 Checking BCR private directory: $privatePath")

        // 1. 先尝试普通访问（可能已经有权限）
        val dir = File(privatePath)
        if (dir.exists() && dir.canRead()) {
            Log.i(TAG, "✅ BCR directory accessible (normal): $privatePath")
            return dir
        }

        // 2. 普通访问失败，使用 Root 权限
        Log.w(TAG, "⚠️ Cannot access BCR directory (expected on Android 11+)")
        Log.i(TAG, "🔓 Requesting root access to grant permissions...")

        return try {
            // 使用 Root 检查目录是否存在
            val testCmd = "test -d $privatePath && echo 'exists' || echo 'notfound'"
            val testProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", testCmd))
            val testResult = testProcess.inputStream.bufferedReader().readText().trim()
            testProcess.waitFor()

            if (testResult != "exists") {
                Log.e(TAG, "❌ BCR directory does not exist: $privatePath")
                Log.e(TAG, "   Please check BCR settings and ensure it's recording")
                return null
            }

            Log.i(TAG, "✅ BCR directory exists (verified with root)")

            // 修改目录权限，让我们的 App 可以访问
            Log.i(TAG, "🔧 Changing permissions: chmod -R 755 $privatePath")
            val chmodCmd = "chmod -R 755 $privatePath"
            val chmodProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", chmodCmd))
            val chmodResult = chmodProcess.waitFor()

            if (chmodResult != 0) {
                Log.e(TAG, "❌ chmod failed with exit code: $chmodResult")
                return null
            }

            Log.i(TAG, "✅ Permissions changed successfully")

            // Android 11+ SELinux 仍然可能阻止访问，使用 Root 验证
            Log.i(TAG, "🔍 Verifying directory access with root...")
            val lsCmd = "ls -la $privatePath | head -5"
            val lsProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", lsCmd))
            val lsOutput = lsProcess.inputStream.bufferedReader().readText()
            lsProcess.waitFor()

            if (lsOutput.isNotEmpty()) {
                Log.i(TAG, "✅ BCR directory accessible via root")
                Log.d(TAG, "📂 Directory listing (first 5 items):\n$lsOutput")
                
                // 即使 dir.canRead() 返回 false，我们也知道可以通过 Root 访问
                // 返回目录对象，后续操作需要使用 Root 命令
                return dir
            } else {
                Log.e(TAG, "❌ Cannot list directory even with root")
                return null
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Root access failed", e)
            Log.e(TAG, "   Make sure:")
            Log.e(TAG, "   1. Device is rooted (Magisk installed)")
            Log.e(TAG, "   2. Grant root permission when prompted")
            Log.e(TAG, "   3. BCR is installed and has recorded at least once")
            null
        }
    }

    /**
     * 已废弃：FileObserver 在 SELinux 限制下不可靠
     * 现在使用轮询 + Root 复制的方式
     */
    @Deprecated("No longer used - using polling + Root copy instead")
    private fun startFileObserver(dir: File) {
        Log.w(TAG, "⚠️ startFileObserver called but no longer used (FileObserver unreliable with SELinux)")
    }

    /**
     * 周期性监控 - 每10秒生成快照并检测
     */
    /**
     * 已废弃：旧的周期性监控方法
     * 现在使用 startMonitoringLoop()
     */
    @Deprecated("Replaced by startMonitoringLoop()")
    private fun startPeriodicMonitoring(dir: File) {
        Log.w(TAG, "⚠️ startPeriodicMonitoring called but deprecated")
    }

    /**
     * 处理音频快照 - 核心逻辑
     * 1. 创建完整文件的临时副本
     * 2. 使用 FFmpeg 提取最后 10 秒并转换为 WAV
     * 3. 上传检测
     */
    private suspend fun processAudioSnapshot(sourceFile: File) {
        withContext(Dispatchers.IO) {
            var tempSourceCopy: File? = null
            var wavChunk: File? = null

            try {
                val currentSize = sourceFile.length()
                snapshotCount++

                Log.i(TAG, "📸 Snapshot #$snapshotCount - Processing ${sourceFile.name}")
                Log.d(
                    TAG,
                    "📊 File size: $currentSize bytes (growth: ${currentSize - lastFileSize} bytes)"
                )

                // 1. 创建源文件的临时快照副本（防止读取时文件被修改）
                tempSourceCopy = createFileSnapshot(sourceFile)
                if (tempSourceCopy == null || !tempSourceCopy.exists()) {
                    Log.e(TAG, "❌ Failed to create file snapshot")
                    return@withContext
                }

                // 2. 使用 MediaCodec 提取最后 10 秒音频并转换为 WAV
                wavChunk = extractLast10SecondsAsWav(tempSourceCopy, sourceFile.extension)

                if (wavChunk == null || !wavChunk.exists() || wavChunk.length() < 1000) {
                    Log.w(TAG, "⚠️ WAV chunk too small or missing, skipping analysis")
                    return@withContext
                }

                Log.i(
                    TAG,
                    "✅ WAV chunk created: ${wavChunk.name}, size: ${wavChunk.length()} bytes"
                )

                // 3. 上传并分析
                val uri = Uri.fromFile(wavChunk)
                val result = fileUploadManager.uploadAndTestFile(
                    uri = uri,
                    fileName = wavChunk.name,
                    groundTruth = "Unknown"
                ) { progress ->
                    Log.d(TAG, "📤 Upload progress: $progress")
                }

                if (result != null) {
                    Log.i(TAG, "🔍 Analysis result for snapshot #$snapshotCount:")
                    Log.i(TAG, "   Decision: ${result.llmDecision}")
                    Log.i(TAG, "   Confidence: ${result.confidence}")

                    // 如果检测到钓鱼，发送警报并推送到 PhishingDataHub
                    if (result.llmDecision == "PHISHING" && result.confidence > 0.7) {
                        sendPhishingAlert(result, snapshotCount)
                    }
                } else {
                    Log.w(TAG, "⚠️ No analysis result returned")
                }

                // 更新最后处理的文件大小
                lastFileSize = currentSize

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing audio snapshot #$snapshotCount", e)
            } finally {
                // 清理临时文件
                try {
                    tempSourceCopy?.delete()
                    wavChunk?.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Error cleaning up temp files", e)
                }
            }
        }
    }

    /**
     * 创建源文件的快照副本
     * 使用 RandomAccessFile 进行安全的文件复制，即使文件正在被写入
     */
    private fun createFileSnapshot(sourceFile: File): File? {
        return try {
            val snapshotDir = File(context.cacheDir, "bcr_snapshots")
            if (!snapshotDir.exists()) {
                snapshotDir.mkdirs()
            }

            val timestamp = System.currentTimeMillis()
            val snapshotFile = File(snapshotDir, "snapshot_${timestamp}.${sourceFile.extension}")

            // 使用 RandomAccessFile 进行安全复制
            RandomAccessFile(sourceFile, "r").use { input ->
                snapshotFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            Log.d(
                TAG,
                "📋 Snapshot created: ${snapshotFile.name}, size: ${snapshotFile.length()} bytes"
            )
            snapshotFile

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating snapshot", e)
            null
        }
    }

    /**
     * 使用 Android MediaCodec 提取音频文件的最后 N 秒并转换为 WAV 格式
     *
     * @param sourceFile 源音频文件（opus/m4a/ogg等）
     * @param sourceExtension 源文件扩展名
     * @return WAV 格式的音频片段文件
     */
    private fun extractLast10SecondsAsWav(sourceFile: File, sourceExtension: String): File? {
        return try {
            val outputDir = File(context.cacheDir, "bcr_wav_chunks")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }

            val timestamp = System.currentTimeMillis()
            val outputFile = File(outputDir, "chunk_${timestamp}_${snapshotCount}.wav")

            // 使用 MediaExtractor + MediaCodec 解码音频
            val extractor = MediaExtractor()
            extractor.setDataSource(sourceFile.absolutePath)

            // 找到音频轨道
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex == -1) {
                Log.e(TAG, "❌ No audio track found in file")
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            // 获取音频参数
            val durationUs = format.getLong(MediaFormat.KEY_DURATION) // 微秒
            val durationSec = durationUs / 1_000_000.0

            Log.d(TAG, "⏱️ Audio duration: ${durationSec}s")

            // 计算起始时间（最后 10 秒）
            val startTimeSec = maxOf(0.0, durationSec - AUDIO_CHUNK_DURATION_SECONDS)
            val startTimeUs = (startTimeSec * 1_000_000).toLong()

            Log.d(TAG, "✂️ Extracting from ${startTimeSec}s to ${durationSec}s")

            // 定位到起始位置
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            // 解码音频数据 (限制解码时长为 AUDIO_CHUNK_DURATION_SECONDS 秒)
            val pcmData = decodeAudioToPcm(extractor, audioTrackIndex, format, durationUs)
            extractor.release()

            if (pcmData.isEmpty()) {
                Log.e(TAG, "❌ Failed to decode audio")
                return null
            }

            // 获取实际采样率和声道数
            val originalSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val originalChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            Log.d(TAG, "🎵 Original format: ${originalSampleRate}Hz, ${originalChannelCount} channel(s)")

            // 目标格式: 16kHz 单声道 (减小文件大小,适合语音识别)
            val targetSampleRate = 16000
            val targetChannelCount = 1
            
            // 重采样和转换为目标格式
            val resampledPcm = resamplePcm(
                pcmData, 
                originalSampleRate, 
                targetSampleRate,
                originalChannelCount,
                targetChannelCount
            )
            
            Log.d(TAG, "🔄 Resampled: ${originalSampleRate}Hz → ${targetSampleRate}Hz, ${originalChannelCount}ch → ${targetChannelCount}ch")
            Log.d(TAG, "📊 Size: ${pcmData.size} → ${resampledPcm.size} bytes")

            // 转换 PCM 为 WAV
            writePcmToWav(resampledPcm, outputFile, targetSampleRate, targetChannelCount)

            if (outputFile.exists() && outputFile.length() > 0) {
                Log.i(
                    TAG,
                    "✅ WAV extraction successful: ${outputFile.name}, size: ${outputFile.length()} bytes"
                )
                return outputFile
            } else {
                Log.e(TAG, "❌ Output WAV file is empty")
                return null
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error extracting audio with MediaCodec", e)
            null
        }
    }

    /**
     * 使用 MediaCodec 解码音频为 PCM 数据
     * 
     * @param extractor MediaExtractor 实例 (已定位到起始位置)
     * @param trackIndex 音频轨道索引
     * @param format 音频格式
     * @param endTimeUs 结束时间(微秒), 用于限制解码时长
     */
    private fun decodeAudioToPcm(
        extractor: MediaExtractor,
        trackIndex: Int,
        format: MediaFormat,
        endTimeUs: Long
    ): ByteArray {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return ByteArray(0)
        val codec = MediaCodec.createDecoderByType(mime)

        codec.configure(format, null, null, 0)
        codec.start()

        val pcmBuffer = mutableListOf<Byte>()
        val bufferInfo = MediaCodec.BufferInfo()
        var isDecoding = true
        val timeoutUs = 10000L // 10ms
        
        // 记录起始时间,用于限制解码时长
        val startTimeUs = extractor.sampleTime

        try {
            while (isDecoding) {
                // 输入数据到解码器
                val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()

                    val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                    if (sampleSize < 0) {
                        // 输入结束
                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        
                        // ✅ 检查是否已超过目标结束时间
                        if (presentationTimeUs >= endTimeUs) {
                            Log.d(TAG, "⏹️ Reached target duration at ${presentationTimeUs / 1_000_000.0}s")
                            codec.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        } else {
                            codec.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                presentationTimeUs,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                // 从解码器获取输出
                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                        if (bufferInfo.size > 0 && outputBuffer != null) {
                            // 读取 PCM 数据
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(chunk, 0, bufferInfo.size)
                            pcmBuffer.addAll(chunk.toList())
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isDecoding = false
                        }
                    }

                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Output format changed: ${codec.outputFormat}")
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        return pcmBuffer.toByteArray()
    }

    /**
     * PCM 重采样和声道转换
     * 
     * @param pcmData 原始 PCM 数据 (16-bit little-endian)
     * @param sourceSampleRate 源采样率
     * @param targetSampleRate 目标采样率
     * @param sourceChannels 源声道数
     * @param targetChannels 目标声道数
     * @return 重采样后的 PCM 数据
     */
    private fun resamplePcm(
        pcmData: ByteArray,
        sourceSampleRate: Int,
        targetSampleRate: Int,
        sourceChannels: Int,
        targetChannels: Int
    ): ByteArray {
        // 如果采样率和声道数都相同,直接返回
        if (sourceSampleRate == targetSampleRate && sourceChannels == targetChannels) {
            return pcmData
        }

        // 将字节数组转换为 16-bit 采样点
        val sourceSamples = pcmData.size / 2 / sourceChannels
        val sourcePcm16 = ShortArray(sourceSamples * sourceChannels)
        
        for (i in sourcePcm16.indices) {
            val byteIndex = i * 2
            sourcePcm16[i] = (
                (pcmData[byteIndex].toInt() and 0xFF) or
                (pcmData[byteIndex + 1].toInt() shl 8)
            ).toShort()
        }

        // 1️⃣ 先进行声道转换 (立体声→单声道: 取平均值)
        val monoSourcePcm = if (sourceChannels > 1 && targetChannels == 1) {
            ShortArray(sourceSamples) { i ->
                var sum = 0
                for (ch in 0 until sourceChannels) {
                    sum += sourcePcm16[i * sourceChannels + ch]
                }
                (sum / sourceChannels).toShort()
            }
        } else if (sourceChannels == 1 && targetChannels > 1) {
            // 单声道→立体声: 复制到所有声道
            ShortArray(sourceSamples * targetChannels) { i ->
                sourcePcm16[i / targetChannels]
            }
        } else {
            sourcePcm16
        }

        // 2️⃣ 重采样 (使用线性插值)
        val resampleRatio = targetSampleRate.toDouble() / sourceSampleRate
        val targetSamples = (sourceSamples * resampleRatio).toInt()
        val targetPcm16 = ShortArray(targetSamples * targetChannels)

        for (i in 0 until targetSamples) {
            // 计算源采样点位置
            val sourcePos = i / resampleRatio
            val sourceIndex = sourcePos.toInt()
            val fraction = sourcePos - sourceIndex

            for (ch in 0 until targetChannels) {
                val sample = if (sourceIndex + 1 < sourceSamples) {
                    // 线性插值
                    val s1 = monoSourcePcm[sourceIndex * targetChannels + ch % sourceChannels].toDouble()
                    val s2 = monoSourcePcm[(sourceIndex + 1) * targetChannels + ch % sourceChannels].toDouble()
                    (s1 + (s2 - s1) * fraction).toInt().toShort()
                } else {
                    // 边界处理
                    monoSourcePcm[sourceIndex * targetChannels + ch % sourceChannels]
                }
                targetPcm16[i * targetChannels + ch] = sample
            }
        }

        // 转换回字节数组
        val output = ByteArray(targetPcm16.size * 2)
        for (i in targetPcm16.indices) {
            val sample = targetPcm16[i].toInt()
            output[i * 2] = (sample and 0xFF).toByte()
            output[i * 2 + 1] = (sample shr 8).toByte()
        }

        return output
    }

    /**
     * 将 PCM 数据写入 WAV 文件
     */
    private fun writePcmToWav(
        pcmData: ByteArray,
        outputFile: File,
        sampleRate: Int,
        channels: Int
    ) {
        FileOutputStream(outputFile).use { out ->
            val dataSize = pcmData.size
            val byteRate = sampleRate * channels * 2 // 16-bit = 2 bytes

            // WAV 文件头（44 字节）
            out.write("RIFF".toByteArray())
            out.write(intToBytes(36 + dataSize)) // 文件大小 - 8
            out.write("WAVE".toByteArray())

            // fmt 子块
            out.write("fmt ".toByteArray())
            out.write(intToBytes(16)) // fmt 子块大小
            out.write(shortToBytes(1)) // 音频格式 (PCM)
            out.write(shortToBytes(channels.toShort())) // 声道数
            out.write(intToBytes(sampleRate)) // 采样率
            out.write(intToBytes(byteRate)) // 字节率
            out.write(shortToBytes((channels * 2).toShort())) // 块对齐
            out.write(shortToBytes(16)) // 位深度

            // data 子块
            out.write("data".toByteArray())
            out.write(intToBytes(dataSize))
            out.write(pcmData)
        }
    }

    /**
     * Int 转小端字节数组
     */
    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    /**
     * Short 转小端字节数组
     */
    private fun shortToBytes(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    /**
     * 处理录音结束时的最后一次快照
     */
    private suspend fun processFinalSnapshot(file: File) {
        Log.i(TAG, "🏁 Processing final snapshot for: ${file.name}")
        processAudioSnapshot(file)
    }

    /**
     * 发送钓鱼警报并推送到 PhishingDataHub
     *
     * 当检测到钓鱼内容时（decision=PHISHING, confidence>0.7），
     * 自动推送警报数据到中央数据中心，供 CoreProtectionService 等组件消费。
     *
     * @param result Dify API 返回的检测结果
     * @param snapshotNumber 快照编号（用于追踪）
     */
    private suspend fun sendPhishingAlert(result: FileTestResult, snapshotNumber: Int) {
        Log.w(TAG, "")
        Log.w(TAG, "⚠️ ═══════════════════════════════════════")
        Log.w(TAG, "⚠️  PHISHING DETECTED - Snapshot #$snapshotNumber")
        Log.w(TAG, "⚠️ ═══════════════════════════════════════")
        Log.w(TAG, "📊 Confidence: ${String.format("%.2f%%", result.confidence * 100)}")
        Log.w(TAG, "💬 Explanation: ${result.llmExplanation}")
        Log.w(TAG, "📁 File: ${result.fileName}")
        Log.w(TAG, "⚠️ ═══════════════════════════════════════")
        Log.w(TAG, "")

        // 推送到 PhishingDataHub 供其他组件消费
        try {
            val phishingData = PhishingData(
                dataType = "PhoneCall",
                content = "通话录音检测到钓鱼内容（快照 #$snapshotNumber）",
                metadata = mapOf(
                    "source" to "bcr_monitor",
                    "decision" to result.llmDecision,
                    "confidence" to String.format("%.4f", result.confidence),
                    "explanation" to result.llmExplanation,
                    "fileName" to result.fileName,
                    "snapshotNumber" to snapshotNumber.toString(),
                    "processingTime" to result.processingTime.toString(),
                    "timestamp" to System.currentTimeMillis().toString()
                )
            )

            PhishingDataHub.addData(phishingData)
            Log.i(TAG, "✅ 钓鱼警报已推送到 PhishingDataHub")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 推送到 PhishingDataHub 失败", e)
        }
    }

    /**
     * 使用 Root 权限列出目录中的文件
     * （解决 SELinux 限制导致 dir.listFiles() 返回 null 的问题）
     */
    private fun listFilesWithRoot(dir: File): List<File> {
        return try {
            val path = dir.absolutePath
            // 使用 ls 命令列出文件，只显示文件名（不含路径）
            val lsCmd = "ls -1 $path 2>/dev/null"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", lsCmd))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (output.isBlank()) {
                Log.d(TAG, "📭 Directory is empty or inaccessible: $path")
                return emptyList()
            }

            // 解析文件名列表
            output.trim().split("\n")
                .filter { it.isNotBlank() }
                .map { filename -> File(dir, filename) }
                .also { files ->
                    Log.d(TAG, "📂 Found ${files.size} file(s) in $path (via root)")
                }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to list files with root", e)
            emptyList()
        }
    }

    /**
     * 使用 Root 权限获取文件大小（字节）
     */
    private fun getFileSizeWithRoot(file: File): Long {
        return try {
            val path = file.absolutePath
            val statCmd = "stat -c '%s' \"$path\" 2>/dev/null"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", statCmd))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            
            output.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to get file size with root: ${file.name}")
            0L
        }
    }

    /**
     * 使用 Root 权限获取文件修改时间（Unix 时间戳，毫秒）
     */
    private fun getFileModTimeWithRoot(file: File): Long {
        return try {
            val path = file.absolutePath
            // %Y = 修改时间戳（秒）
            val statCmd = "stat -c '%Y' \"$path\" 2>/dev/null"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", statCmd))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            
            (output.toLongOrNull() ?: 0L) * 1000 // 转换为毫秒
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to get file mod time with root: ${file.name}")
            0L
        }
    }

    /**
     * 停止监控
     */
    fun stopCollection() {
        try {
            Log.i(TAG, "🛑 Stopping BCR monitoring...")

            fileObserver?.stopWatching()
            fileObserver = null

            monitorJob?.cancel()
            monitorJob = null

            currentRecordingFile = null
            snapshotCount = 0
            lastFileSize = 0L

            // 清理临时文件夹
            cleanupTempFiles()

            scope.cancel()

            Log.i(TAG, "✅ BCR monitoring stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping monitoring", e)
        }
    }

    /**
     * 清理所有临时文件
     */
    private fun cleanupTempFiles() {
        try {
            val snapshotDir = File(context.cacheDir, "bcr_snapshots")
            val wavChunkDir = File(context.cacheDir, "bcr_wav_chunks")

            listOf(snapshotDir, wavChunkDir).forEach { dir ->
                if (dir.exists()) {
                    val deletedCount = dir.listFiles()?.count { it.delete() } ?: 0
                    Log.d(TAG, "🗑️ Cleaned up $deletedCount files from ${dir.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error cleaning up temp files", e)
        }
    }
}
