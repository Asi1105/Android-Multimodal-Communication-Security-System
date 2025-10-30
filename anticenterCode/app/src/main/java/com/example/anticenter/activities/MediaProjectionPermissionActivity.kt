package com.example.anticenter.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * MediaProjection权限请求Activity
 * 用于从通知中启动并处理MediaProjection权限请求
 */
class MediaProjectionPermissionActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_MEETING_ID = "meeting_id"
        private const val TAG = "MediaProjectionPermissionActivity"
    }
    
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "MediaProjection permission result: ${result.resultCode}")
        
        if (result.resultCode == Activity.RESULT_OK) {
            val meetingId = intent.getStringExtra(EXTRA_MEETING_ID) ?: "unknown"
            Log.d(TAG, "✅ MediaProjection permission granted for meeting: $meetingId")
            
            // 发送显式广播通知Service权限已获得
            val successIntent = Intent("com.example.anticenter.MEDIA_PROJECTION_GRANTED").apply {
                setPackage(packageName) // 设置包名，使其成为显式广播
                putExtra("meeting_id", meetingId)
                putExtra("consent", result.data)
            }
            sendBroadcast(successIntent)
            Log.d(TAG, "📡 Broadcast sent: MEDIA_PROJECTION_GRANTED")
            
        } else {
            val meetingId = intent.getStringExtra(EXTRA_MEETING_ID) ?: "unknown"
            Log.w(TAG, "❌ MediaProjection permission denied for meeting: $meetingId")
            
            // 发送显式广播通知Service权限被拒绝
            val failIntent = Intent("com.example.anticenter.MEDIA_PROJECTION_DENIED").apply {
                setPackage(packageName) // 设置包名，使其成为显式广播
                putExtra("meeting_id", meetingId)
            }
            sendBroadcast(failIntent)
            Log.d(TAG, "📡 Broadcast sent: MEDIA_PROJECTION_DENIED")
        }
        
        // 完成Activity
        finish()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "📱 MediaProjectionPermissionActivity started")
        
        try {
            // 获取MediaProjectionManager并创建权限请求Intent
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val permissionIntent = mediaProjectionManager.createScreenCaptureIntent()
            
            // 启动权限请求
            mediaProjectionLauncher.launch(permissionIntent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaProjection permission request", e)
            
            // 发送失败显式广播
            val failIntent = Intent("com.example.anticenter.MEDIA_PROJECTION_DENIED").apply {
                setPackage(packageName) // 设置包名，使其成为显式广播
                putExtra("meeting_id", intent.getStringExtra(EXTRA_MEETING_ID) ?: "unknown")
            }
            sendBroadcast(failIntent)
            Log.d(TAG, "📡 Broadcast sent: MEDIA_PROJECTION_DENIED (error case)")
            finish()
        }
    }
}