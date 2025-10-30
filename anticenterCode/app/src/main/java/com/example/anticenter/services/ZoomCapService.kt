package com.example.anticenter.services

import android.R
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import com.example.anticenter.collectors.ZoomCollector
import com.example.anticenter.BuildConfig

/**
 * ✅ ZoomCapService - 录屏、截图、Zoom 音频捕获 (Root)、多重检测集成。
 * 
 * 【最终集成版本】结合了音频切片/命名修正、启动清理以及双重检测逻辑：
 * 1. Reality Defender (RD) - 深度伪造检测 (视频、音频、图片)
 * 2. Dify Voice Detector - 语音钓鱼检测 (音频)
 * 
 * 音频文件同时提交给两个检测器进行并行分析，提供全面的威胁检测能力。
 */
class ZoomCapService : Service() {

    private val TAG = "ZoomMonitor"
    private val CHANNEL_ID = "zoom_monitor_service"
    private val ACTION_STOP = "com.example.anticenter.services.ACTION_STOP"

    private val FILENAME_REGEX =
        Regex("zoom_(mic|tap)_(\\d{8})_(\\d{6})_(\\d+)\\.pcm", RegexOption.IGNORE_CASE)

    private val VIDEO_DIR = "ZoomVideos"
    private val IMAGE_DIR = "ZoomImages"
    private val AUDIO_DIR = "ZoomAudio"

    private lateinit var mediaProjection: MediaProjection
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecorderSurface: android.view.Surface? = null  // 保存当前 Surface 引用
    private lateinit var imageReader: ImageReader

    private lateinit var projHt: HandlerThread
    private lateinit var projHandler: Handler
    private lateinit var rotateHandler: Handler

    private lateinit var videoFolder: File
    private lateinit var imageFolder: File
    private lateinit var audioFolder: File

    private var segIndex = 0
    private var sessionBase: String = ""
    private var isRunning = false
    private var audioWatcherRunning = false

    // 检测器实例
    private lateinit var rdDetector: RealityDefenderDetection
    private lateinit var difyDetector: DifyVoiceDetector
    
    // ZoomCollector 引用，确保单例已初始化
    private var zoomCollector: ZoomCollector? = null

    private fun L(msg: String) = Log.i(TAG, msg)

    // -------------------- 启动 --------------------
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServiceGracefully()
            return START_NOT_STICKY
        }
        try {
            val notif = buildNotification("Starting…")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(1, notif)

            val consent: Intent? = if (Build.VERSION.SDK_INT >= 32) {
                intent?.getParcelableExtra("consent", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("consent")
            }
            if (consent == null) { stopSelf(); return START_NOT_STICKY }

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(Activity.RESULT_OK, consent)
                ?: run { stopSelf(); return START_NOT_STICKY }
            mediaProjection = projection

            projHt = HandlerThread("MP-CB").apply { start() }
            projHandler = Handler(projHt.looper)
            
            // 保存回调引用以便后续注销
            mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    L("MediaProjection stopped by system")
                    stopServiceGracefully()
                }
            }
            mediaProjection.registerCallback(mediaProjectionCallback!!, projHandler)

            sessionBase = "session_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
            videoFolder = File(filesDir, VIDEO_DIR).apply { mkdirs() }
            imageFolder = File(filesDir, IMAGE_DIR).apply { mkdirs() }
            audioFolder = File(filesDir, AUDIO_DIR).apply { mkdirs() }

            val rotateHt = HandlerThread("VideoRotate").apply { start() }
            rotateHandler = Handler(rotateHt.looper)

            initAndStartRecording()
            scheduleNextVideoRotation()
            isRunning = true

            // 初始化 RD 检测器
            val apiKey = BuildConfig.RD_API_KEY
            Log.i(TAG, "RD_API_KEY length=" + apiKey.length)
            rdDetector = RealityDefenderDetection(apiKey)

            // 初始化 Dify 语音钓鱼检测器
            val difyApiKey = BuildConfig.DIFY_API_KEY
            Log.i(TAG, "DIFY_API_KEY length=" + difyApiKey.length)
            difyDetector = DifyVoiceDetector(difyApiKey)
            Log.i(TAG, "✅ DifyVoiceDetector initialized successfully")

            // 确保 ZoomCollector 单例已初始化
            zoomCollector = ZoomCollector.getInstance()
            if (zoomCollector == null) {
                Log.w(TAG, "⚠️ ZoomCollector instance is NULL! Creating a fallback instance...")
                // 创建一个备用实例（如果 MainActivity 还没初始化）
                zoomCollector = ZoomCollector(applicationContext, com.example.anticenter.data.PhishingDataHub)
                Log.i(TAG, "✅ Fallback ZoomCollector instance created in Service")
            } else {
                Log.i(TAG, "✅ ZoomCollector instance found from MainActivity")
            }

            // 启动音频监控（恢复功能）
            if (!audioWatcherRunning) {
                audioWatcherRunning = true
                startAudioWatcher()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Startup error: ${e.message}", e)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        audioWatcherRunning = false
        
        // 停止定时任务
        try { rotateHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
        
        // 停止并释放 MediaRecorder (包含 Surface 释放)
        try { 
            stopAndReleaseRecorder(mediaRecorder, currentRecorderSurface)
            mediaRecorder = null
            currentRecorderSurface = null
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder cleanup error: ${e.message}", e)
        }
        
        // 释放 VirtualDisplay (会自动释放其 Surface)
        try { 
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}
        
        // 释放 ImageReader (包含其 Surface)
        try { 
            if (::imageReader.isInitialized) {
                imageReader.surface?.release()
                imageReader.close()
            }
        } catch (_: Exception) {}
        
        // 停止 MediaProjection
        try {
            if (::mediaProjection.isInitialized) {
                mediaProjectionCallback?.let { callback ->
                    mediaProjection.unregisterCallback(callback)
                }
                mediaProjection.stop()
            }
        } catch (_: Exception) {}
        
        // 停止 HandlerThread
        try {
            if (::projHt.isInitialized) {
                projHt.quitSafely()
            }
        } catch (_: Exception) {}
        
        // 关闭 RealityDefender SDK (在后台线程)
        try { 
            if (::rdDetector.isInitialized) {
                Thread {
                    try {
                        rdDetector.shutdown()
                    } catch (e: Exception) {
                        Log.e(TAG, "RD shutdown error: ${e.message}", e)
                    }
                }.start()
            }
        } catch (_: Exception) {}
        
        // 释放 Dify 检测器资源
        try { 
            if (::difyDetector.isInitialized) difyDetector.shutdown() 
        } catch (_: Exception) {}

        super.onDestroy()
        Log.i(TAG, "✅ ZoomCapService destroyed, all resources released")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------- 屏幕录制 --------------------
    private fun initAndStartRecording() {
        val dm: DisplayMetrics = resources.displayMetrics
        val w = dm.widthPixels.coerceAtMost(1280)
        val h = dm.heightPixels.coerceAtMost(720)
        val dpi = dm.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)

        val firstFile = getNewSegmentFile(segIndex)
        mediaRecorder = buildPreparedRecorder(firstFile, w, h)
        currentRecorderSurface = mediaRecorder?.surface  // 保存 Surface 引用

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ZoomMonitorVD", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            currentRecorderSurface, null, null
        )

        mediaRecorder?.start()
        L("Initial recording started for ${firstFile.name}")
        segIndex += 1
        updateNotification("Recording segment #${segIndex-1}")
    }

    private fun scheduleNextVideoRotation() {
        rotateHandler.postDelayed({
            captureFrameAndSaveImage()
            rotateVideoOnce()
        }, 10_000L)
    }

    private fun rotateVideoOnce() {
        if (!isRunning) return
        try {
            val prevRecorder = mediaRecorder
            val prevSurface = currentRecorderSurface  // 保存旧的 Surface 引用
            val finishedFile = getSegmentFileByIndex(segIndex - 1)

            val dm: DisplayMetrics = resources.displayMetrics
            val w = dm.widthPixels.coerceAtMost(1280)
            val h = dm.heightPixels.coerceAtMost(720)
            val fileNext = getNewSegmentFile(segIndex)
            val nextRecorder = buildPreparedRecorder(fileNext, w, h)
            val nextSurface = nextRecorder.surface  // 获取新的 Surface

            virtualDisplay?.setSurface(nextSurface)

            nextRecorder.start()
            mediaRecorder = nextRecorder
            currentRecorderSurface = nextSurface  // 更新当前 Surface 引用
            segIndex += 1
            updateNotification("Recording segment #${segIndex-1}")

            Thread {
                stopAndReleaseRecorder(prevRecorder, prevSurface)  // 传递 Surface 引用
                onVideoSegmentReady(finishedFile)
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "rotateVideoOnce error: ${e.message}", e)
        } finally {
            scheduleNextVideoRotation()
        }
    }

    private fun captureFrameAndSaveImage() {
        rotateHandler.post {
            val vd = virtualDisplay ?: return@post
            val currentSurface = vd.surface
            
            // 临时切换到 ImageReader 的 Surface
            vd.setSurface(imageReader.surface)
            
            rotateHandler.postDelayed({
                try {
                    val image = imageReader.acquireLatestImage()
                    // 立即切换回原 Surface
                    vd.setSurface(currentSurface)
                    
                    image?.use {
                        val fullBmp = imageToBitmap(it) ?: return@use
                        val imageFile = File(imageFolder, "full_frame_${segIndex - 1}.jpg")
                        // MARK: 图像检测已禁用
                        // saveBitmapToFile(fullBmp, imageFile)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "captureFrameAndSaveImage error: ${e.message}", e)
                    // 确保即使出错也恢复原 Surface
                    try { vd.setSurface(currentSurface) } catch (_: Exception) {}
                }
            }, 100)
        }
    }

    // -------------------- 音频捕获 --------------------
    private fun startAudioWatcher() {
        Thread {
            val sampleRate = 48000
            val channels = 1
            val zoomDir = "/sdcard/Android/data/us.zoom.videomeetings/files/ZoomAudio"
            val micDir = File(audioFolder, "mic").apply { mkdirs() }
            val tapDir = File(audioFolder, "tap").apply { mkdirs() }

            try {
                val cleanCmd = "rm -f $zoomDir/zoom_mic_*.pcm $zoomDir/zoom_tap_*.pcm"
                Runtime.getRuntime().exec(arrayOf("su", "-c", cleanCmd)).waitFor()
                L("🧹 Cleaned old PCM files from Zoom hook directory.")
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup error: ${e.message}", e)
            }

            L("🎧 AudioWatcher started, checking every 10s")

            while (audioWatcherRunning) {
                try {
                    val moveCmd = """
                        mv $zoomDir/zoom_mic_*.pcm ${micDir.absolutePath}/ 2>/dev/null;
                        mv $zoomDir/zoom_tap_*.pcm ${tapDir.absolutePath}/ 2>/dev/null;
                        chmod 666 ${micDir.absolutePath}/*.pcm ${tapDir.absolutePath}/*.pcm 2>/dev/null;
                    """.trimIndent()
                    Runtime.getRuntime().exec(arrayOf("su", "-c", moveCmd)).waitFor()

                    listOf(micDir, tapDir).forEach { dir ->
                        dir.listFiles { f -> f.name.endsWith(".pcm") }?.forEach { pcmFile ->
                            Thread {
                                try {
                                    val newWavName = generateWavName(pcmFile.name)
                                    val wavFile = File(dir, newWavName)
                                    convertPcmToWav(pcmFile, wavFile, sampleRate, channels)
                                    //// MARK: 音频检测已禁用
                                    onAudioSegmentReady(wavFile)
                                    pcmFile.delete()
                                    L("✅ Converted & deleted ${pcmFile.name}")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Convert thread error: ${e.message}", e)
                                }
                            }.start()
                        }
                    }

                    Thread.sleep(10_000)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioWatcher error: ${e.message}", e)
                }
            }
            L("🛑 AudioWatcher stopped.")
        }.start()
    }

    private fun generateWavName(pcmFileName: String): String {
        val match = FILENAME_REGEX.find(pcmFileName)
        return if (match != null) {
            val type = match.groupValues[1]
            val date = match.groupValues[2]
            val time = match.groupValues[3]
            val segment = match.groupValues[4]
            "Zoom_${type}_${date}_${time}_${segment}.wav"
        } else {
            pcmFileName.replace(".pcm", ".wav").replace("zoom_", "Zoom_")
        }
    }

    // -------------------- Reality Defender (RD) 集成 --------------------
    private fun onVideoSegmentReady(file: File) {
        L("onVideoSegmentReady -> ${file.name}")
        try {
            if (::rdDetector.isInitialized && file.exists() && file.length() > 0L) {
                val future = rdDetector.submit(file)
                Log.i(TAG, "📤 Submitted VIDEO to RD: ${file.name}")
                future.whenComplete { result, ex ->
                    if (ex != null) {
                        Log.e(TAG, "❌ RD video detect failed: ${file.name}", ex)
                    } else {
                        val status = result?.status ?: "UNKNOWN"
                        Log.i(TAG, "✅ RD video result(${file.name}): status=$status")
                        Log.i(TAG, "📨 Passing result.status='$status' to ZoomCollector for ${file.name}")
                        
                        // 调用 ZoomCollector 处理检测结果
                        if (zoomCollector != null) {
                            zoomCollector?.processDetectionResult(file, status, "VIDEO")
                            Log.i(TAG, "✅ result.status has been passed to ZoomCollector.processDetectionResult()")
                        } else {
                            Log.e(TAG, "❌ ZoomCollector is NULL! Cannot process VIDEO detection result for ${file.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "submit video to RD failed: ${e.message}", e)
        }
    }

    private fun onAudioSegmentReady(file: File) {
        L("onAudioSegmentReady -> ${file.name}")

        // 检查文件所属文件夹类型
        val parentFolderName = file.parentFile?.name ?: ""

        // 只处理 tap 文件夹中的文件（接收到的音频），跳过 mic 文件夹（说话者音频）
        if (parentFolderName.equals("mic", ignoreCase = true)) {
            Log.i(TAG, "⏭️ Skipping detection for MIC folder file (speaker audio): ${file.name}")
            return
        }

        if (!parentFolderName.equals("tap", ignoreCase = true)) {
            Log.w(TAG, "⚠️ Unknown folder type: $parentFolderName for file ${file.name}, skipping detection")
            return
        }

        Log.i(TAG, "✅ Processing TAP folder file (received audio): ${file.name}")

        // 把完成的音频文件投递给 RD（非阻塞）- 深度伪造检测
        try {
            if (::rdDetector.isInitialized && file.exists() && file.length() > 0L) {
                val future = rdDetector.submit(file)
                Log.i(TAG, "📤 Submitted AUDIO to RD (deepfake detection): ${file.name}")

                future.whenComplete { result, ex ->
                    if (ex != null) {
                        Log.e(TAG, "❌ RD audio detect failed: ${file.name}", ex)
                    } else {
                        val status = result?.status ?: "UNKNOWN"
                        Log.i(TAG, "✅ RD audio result(${file.name}): status=$status")

                        // 传递结果给 ZoomCollector
                        if (zoomCollector != null) {
                            zoomCollector?.processDetectionResult(file, status, "AUDIO")
                            Log.i(TAG, "✅ RD audio result passed to ZoomCollector")
                        } else {
                            Log.e(TAG, "❌ ZoomCollector is NULL! Cannot process AUDIO detection result for ${file.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "submit audio to RD failed: ${e.message}", e)
        }

        // 把完成的音频文件投递给 Dify（非阻塞）- 语音钓鱼检测
        try {
            if (::difyDetector.isInitialized && file.exists() && file.length() > 0L) {
                val future = difyDetector.submit(file)
                Log.i(TAG, "📤 Submitted AUDIO to Dify (voice phishing detection): ${file.name}")

                future.whenComplete { result, ex ->
                    if (ex != null) {
                        Log.e(TAG, "❌ Dify voice phishing detect failed: ${file.name}", ex)
                    } else {
                        val verdict = result?.verdict ?: "UNKNOWN"
                        val confidence = result?.confidence ?: 0.0
                        Log.i(TAG, "✅ Dify voice phishing result(${file.name}): verdict=$verdict, confidence=$confidence")
                        Log.i(TAG, "   Reasons: ${result?.reasons?.joinToString(", ") ?: "none"}")
                        Log.i(TAG, "   Evidence: ${result?.evidence?.joinToString(", ") ?: "none"}")

                        // 传递结果给 ZoomCollector（使用 verdict 作为 status）
                        if (zoomCollector != null) {
                            zoomCollector?.processDetectionResult(file, verdict, "AUDIO_PHISHING")
                            Log.i(TAG, "✅ Dify voice phishing result passed to ZoomCollector")
                        } else {
                            Log.e(TAG, "❌ ZoomCollector is NULL! Cannot process voice phishing result for ${file.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "submit audio to Dify failed: ${e.message}", e)
        }
    }

    private fun saveBitmapToFile(bmp: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            L("saved image -> ${file.absolutePath}")
            
            // 把抓到的图片投递给 RD（非阻塞）
            try {
                if (::rdDetector.isInitialized && file.exists() && file.length() > 0L) {
                    val future = rdDetector.submit(file)
                    Log.i(TAG, "📤 Submitted IMAGE to RD: ${file.name}")

                    future.whenComplete { result, ex ->
                        if (ex != null) {
                            Log.e(TAG, "❌ RD image detect failed: ${file.name}", ex)
                        } else {
                            val status = result?.status ?: "UNKNOWN"
                            Log.i(TAG, "✅ RD image result(${file.name}): status=$status")
                            Log.i(TAG, "📨 Passing result.status='$status' to ZoomCollector for ${file.name}")
                            
                            // 调用 ZoomCollector 处理检测结果
                            if (zoomCollector != null) {
                                zoomCollector?.processDetectionResult(file, status, "IMAGE")
                                Log.i(TAG, "✅ result.status has been passed to ZoomCollector.processDetectionResult()")
                            } else {
                                Log.e(TAG, "❌ ZoomCollector is NULL! Cannot process IMAGE detection result for ${file.name}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "submit image to RD failed: ${e.message}", e)
            }
        } catch (e: Exception) {
            L("saveBitmapToFile error: ${e.message}")
        }
    }

    // -------------------- 通用工具 --------------------
    private fun convertPcmToWav(pcmFile: File, wavFile: File, sr: Int, ch: Int) {
        val byteRate = sr * ch * 2
        val totalAudioLen = pcmFile.length()
        val totalDataLen = totalAudioLen + 36
        val header = ByteArray(44)

        fun putLEInt(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
            header[off + 2] = ((v shr 16) and 0xff).toByte()
            header[off + 3] = ((v shr 24) and 0xff).toByte()
        }

        fun putLEShort(off: Int, v: Short) {
            header[off] = (v.toInt() and 0xff).toByte()
            header[off + 1] = ((v.toInt() shr 8) and 0xff).toByte()
        }

        System.arraycopy("RIFF".toByteArray(), 0, header, 0, 4)
        putLEInt(4, totalDataLen.toInt())
        System.arraycopy("WAVE".toByteArray(), 0, header, 8, 4)
        System.arraycopy("fmt ".toByteArray(), 0, header, 12, 4)
        putLEInt(16, 16)
        putLEShort(20, 1)
        putLEShort(22, ch.toShort())
        putLEInt(24, sr)
        putLEInt(28, byteRate)
        putLEShort(32, (ch * 2).toShort())
        putLEShort(34, 16.toShort())
        System.arraycopy("data".toByteArray(), 0, header, 36, 4)
        putLEInt(40, totalAudioLen.toInt())

        FileInputStream(pcmFile).use { input ->
            FileOutputStream(wavFile).use { out ->
                out.write(header)
                val buffer = ByteArray(1024 * 1024)
                var bytesRead: Int
                while (true) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    out.write(buffer, 0, bytesRead)
                }
            }
        }
        L("✅ WAV saved: ${wavFile.name} (${wavFile.length() / 1024} KB)")
    }

    private fun buildPreparedRecorder(outFile: File, w: Int, h: Int): MediaRecorder {
        val mr = MediaRecorder()
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setVideoSize(w, h)
        mr.setVideoFrameRate(30)
        mr.setVideoEncodingBitRate(3 * 1024 * 1024)
        mr.setOutputFile(outFile.absolutePath)
        mr.prepare()
        return mr
    }

    private fun stopAndReleaseRecorder(mr: MediaRecorder?, surface: android.view.Surface? = null) {
        if (mr == null) return
        try { mr.stop() } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder stop warning: ${e.message}")
        }
        try { mr.reset() } catch (_: Exception) {}
        try { 
            // 优先使用传入的 Surface 引用,如果没有则尝试从 MediaRecorder 获取
            val surfaceToRelease = surface ?: mr.surface
            surfaceToRelease?.release()
            if (surfaceToRelease != null) {
                Log.i(TAG, "✅ Surface released successfully")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Surface release warning: ${e.message}")
        }
        try { mr.release() } catch (_: Exception) {}
    }

    private fun getNewSegmentFile(index: Int) =
        File(videoFolder, "${sessionBase}_${String.format(Locale.US, "%03d", index)}.mp4")

    private fun getSegmentFileByIndex(index: Int) =
        File(videoFolder, "${sessionBase}_${String.format(Locale.US, "%03d", index)}.mp4")

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buf = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val tmp = Bitmap.createBitmap(
            image.width + max(0, rowPadding / pixelStride),
            image.height,
            Bitmap.Config.ARGB_8888
        )
        tmp.copyPixelsFromBuffer(buf)
        return Bitmap.createBitmap(tmp, 0, 0, image.width, image.height)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Zoom Monitor", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, ZoomCapService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_media_play)
            .setContentTitle("Zoom Monitor")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }

    private fun stopServiceGracefully() {
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
