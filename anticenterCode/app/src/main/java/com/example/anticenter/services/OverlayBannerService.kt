package com.example.anticenter.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.Button
import androidx.cardview.widget.CardView
import kotlin.math.max
import com.example.anticenter.R

/**
 * A lightweight overlay service that shows a top banner across apps using TYPE_APPLICATION_OVERLAY.
 * It hosts existing Compose banner components inside a ComposeView added to WindowManager.
 */
class OverlayBannerService : Service() {
    private var wm: WindowManager? = null
    private var overlayView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingShow: Runnable? = null
    // Auto-dismiss related (20s auto-hide with no interaction; any interaction resets timer)
    private var autoDismissRunnable: Runnable? = null
    private var lastInteractionTs: Long = 0L
    private val autoDismissMs: Long = 20_000L
    
    // Expand state related
    private var isExpanded = false
    private var currentMessage = ""
    private var detailsContainer: LinearLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_SHOW -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
                val msg = intent.getStringExtra(EXTRA_MESSAGE) ?: DEFAULT_MESSAGE
                val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
                scheduleShow(title, msg, delayMs)
            }
            ACTION_HIDE -> hideOverlay()
            else -> {
                // default: show with default message if no action specified
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TITLE
                val msg = intent?.getStringExtra(EXTRA_MESSAGE) ?: DEFAULT_MESSAGE
                val delayMs = intent?.getLongExtra(EXTRA_DELAY_MS, 0L) ?: 0L
                scheduleShow(title, msg, delayMs)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
    }

    private fun canDrawOverlays(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
    }

    private fun getStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp,
            context.resources.displayMetrics
        ).toInt()
    }

    private fun scheduleShow(title: String, msg: String, delayMs: Long) {
        // cancel previous pending task if any
        pendingShow?.let { mainHandler.removeCallbacks(it) }
        if (delayMs <= 0L) {
            showOverlay(title, msg)
            return
        }
        val task = Runnable { showOverlay(title, msg) }
        pendingShow = task
        mainHandler.postDelayed(task, delayMs)
        android.util.Log.d(TAG, "Scheduled overlay in ${delayMs}ms")
    }

    private fun showOverlay(title: String, msg: String) {
        if (!canDrawOverlays(this)) {
            android.util.Log.w(TAG, "Overlay permission not granted; abort showing overlay")
            stopSelf()
            return
        }

        if (overlayView == null) {
            // Create Material Design-style CardView container
            val card = CardView(applicationContext).apply {
                radius = dpToPx(applicationContext, 12f).toFloat()
                cardElevation = dpToPx(applicationContext, 8f).toFloat()
                setCardBackgroundColor(Color.parseColor("#FFEBEE")) // FraudWarningBanner background color
                useCompatPadding = true

                val cardParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                        dpToPx(applicationContext, 8f),
                        dpToPx(applicationContext, 4f),
                        dpToPx(applicationContext, 8f),
                        dpToPx(applicationContext, 4f)
                    )
                }
                layoutParams = cardParams
            }

            // Main content row
            val mainRow = LinearLayout(applicationContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dpToPx(applicationContext, 12f),
                    dpToPx(applicationContext, 12f),
                    dpToPx(applicationContext, 12f),
                    dpToPx(applicationContext, 12f)
                )
            }

            // Warning icon: using 24dp VectorDrawable (aligned with Compose Icon.size(24.dp))
            val iconView = ImageView(applicationContext).apply {
                setImageResource(R.drawable.ic_warning_24)
                setColorFilter(Color.parseColor("#D32F2F"))
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(applicationContext, 24f),
                    dpToPx(applicationContext, 24f)
                ).apply {
                    setMargins(0, 0, dpToPx(applicationContext, 12f), 0)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }

            // Text content area
            val textColumn = LinearLayout(applicationContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            // Title (customizable)
            val titleView = TextView(applicationContext).apply {
                text = title
                textSize = 14f
                setTextColor(Color.parseColor("#B71C1C")) // FraudWarningBanner title color
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            // Message content - simplified version (displayed when collapsed)
            val messageView = TextView(applicationContext).apply {
                text = if (msg.length > 50) "${msg.take(50)}..." else msg
                textSize = 12f
                setTextColor(Color.parseColor("#424242")) // FraudWarningBanner message color
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(applicationContext, 2f)
                }
            }

            textColumn.addView(titleView)
            textColumn.addView(messageView)

            // Button area
            val buttonRow = LinearLayout(applicationContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            
            // Expand/collapse arrow button
            val expandButton = ImageView(applicationContext).apply {
                setImageResource(R.drawable.ic_expand_more_24)
                setColorFilter(Color.parseColor("#D32F2F"))
                val size = dpToPx(applicationContext, 20f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(0, 0, dpToPx(applicationContext, 8f), 0)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setOnClickListener {
                    isExpanded = !isExpanded
                    detailsContainer?.visibility = if (isExpanded) View.VISIBLE else View.GONE
                    setImageResource(if (isExpanded) R.drawable.ic_expand_less_24 else R.drawable.ic_expand_more_24)
                    messageView.visibility = if (isExpanded) View.GONE else View.VISIBLE
                    android.util.Log.d(TAG, "Banner ${if (isExpanded) "expanded" else "collapsed"}")
                    resetAutoDismissTimer(reason = "expand_toggle")
                }
            }

            // Close button
            val closeButton = ImageView(applicationContext).apply {
                setImageResource(R.drawable.ic_close_24)
                setColorFilter(Color.parseColor("#D32F2F"))
                val size = dpToPx(applicationContext, 24f)
                layoutParams = LinearLayout.LayoutParams(size, size)
                // Simulate Icon(16dp) inside IconButton(24dp): add 4dp padding on all sides to 24dp container
                setPadding(
                    dpToPx(applicationContext, 4f),
                    dpToPx(applicationContext, 4f),
                    dpToPx(applicationContext, 4f),
                    dpToPx(applicationContext, 4f)
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                // Circular transparent background (click feedback area), similar to Compose IconButton appearance
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                }
                setOnClickListener {
                    hideOverlay()
                }
            }

            buttonRow.addView(expandButton)
            buttonRow.addView(closeButton)

            mainRow.addView(iconView)
            mainRow.addView(textColumn)
            mainRow.addView(buttonRow)

            // Details container - initially hidden
            detailsContainer = LinearLayout(applicationContext).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                // Align with Compose BaseBanner: backgroundColor.copy(alpha = 0.7f)
                // Background color #FFEBEE with 70% transparency -> #B3FFEBEE
                setBackgroundColor(Color.parseColor("#B3FFEBEE"))
                setPadding(
                    dpToPx(applicationContext, 12f),
                    dpToPx(applicationContext, 12f),
                    dpToPx(applicationContext, 12f),
                    dpToPx(applicationContext, 12f)
                )
            }
            
            // Divider line
            val divider = View(applicationContext).apply {
                // Align with Compose Divider: iconColor(red) with 30% transparency -> #4DD32F2F
                setBackgroundColor(Color.parseColor("#4DD32F2F"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    // 0.5dp -> at least 1px to avoid being rounded to 0
                    max(1, dpToPx(applicationContext, 0.5f))
                ).apply {
                    // Column already has 12dp padding, Divider only needs 8dp bottom margin
                    setMargins(0, 0, 0, dpToPx(applicationContext, 8f))
                }
            }
            
            // Full message text
            val fullMessageView = TextView(applicationContext).apply {
                text = msg // Display full message
                textSize = 12f
                setTextColor(Color.parseColor("#424242"))
                // Align with Compose BaseBanner's lineHeight=18.sp, approximately using 1.5x line height
                setLineSpacing(0f, 1.5f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            
            detailsContainer?.addView(divider)
            detailsContainer?.addView(fullMessageView)
            
            // Main row click can also expand/collapse, matching Compose Row(clickable)
            mainRow.isClickable = true
            mainRow.setOnClickListener {
                isExpanded = !isExpanded
                detailsContainer?.visibility = if (isExpanded) View.VISIBLE else View.GONE
                expandButton.setImageResource(if (isExpanded) R.drawable.ic_expand_less_24 else R.drawable.ic_expand_more_24)
                messageView.visibility = if (isExpanded) View.GONE else View.VISIBLE
                resetAutoDismissTimer(reason = "main_row_click")
            }
            
            // Main container includes main row and details
            val mainContainer = LinearLayout(applicationContext).apply {
                orientation = LinearLayout.VERTICAL
                addView(mainRow)
                detailsContainer?.let { addView(it) }
            }
            
            card.addView(mainContainer)

            // Outer container
            val container = LinearLayout(applicationContext).apply {
                orientation = LinearLayout.VERTICAL
                addView(card)
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val flags = (
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = getStatusBarHeight(applicationContext) // Display below status bar
            }

            val manager = getSystemService(WINDOW_SERVICE) as WindowManager
            try {
                manager.addView(container, params)
                wm = manager
                overlayView = container
                android.util.Log.d(TAG, "Material Design overlay added below status bar")
                // Start auto-dismiss timer after initial display
                scheduleAutoDismissTimer()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to add overlay view", e)
                overlayView = null
                wm = null
                stopSelf()
            }
        } else {
            android.util.Log.d(TAG, "Overlay already present")
            // If called again, reset timer (e.g., content update)
            resetAutoDismissTimer(reason = "repeat_show")
        }
    }

    private fun hideOverlay() {
        // cancel pending task
        pendingShow?.let {
            mainHandler.removeCallbacks(it)
            pendingShow = null
        }
        // Cancel auto-hide task
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
        val view = overlayView
        val manager = wm
        if (view != null && manager != null) {
            try {
                manager.removeView(view)
                android.util.Log.d(TAG, "Overlay removed")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "removeView failed", e)
            }
        }
        overlayView = null
        wm = null
        stopSelf()
    }

    companion object {
        private const val TAG = "OverlayBannerService"
        const val ACTION_SHOW = "com.example.anticenter.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.example.anticenter.action.HIDE_OVERLAY"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_DELAY_MS = "extra_delay_ms"
        private const val DEFAULT_TITLE = "Fraud Risk Warning"
        private const val DEFAULT_MESSAGE = "Suspicious activity detected. Please be cautious."
    }

    // ---------- Auto-dismiss logic ----------
    private fun scheduleAutoDismissTimer() {
        lastInteractionTs = System.currentTimeMillis()
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            // If no interaction refresh during this period, execute hide
            val elapsed = System.currentTimeMillis() - lastInteractionTs
            if (elapsed >= autoDismissMs) {
                android.util.Log.d(TAG, "Auto dismiss triggered after ${elapsed}ms")
                hideOverlay()
            } else {
                // Theoretically shouldn't reach here, as reset will reschedule
                android.util.Log.d(TAG, "Auto dismiss skipped; elapsed=${elapsed}ms < ${autoDismissMs}ms")
                scheduleAutoDismissTimer()
            }
        }
        autoDismissRunnable = r
        mainHandler.postDelayed(r, autoDismissMs)
        android.util.Log.d(TAG, "Auto dismiss scheduled in ${autoDismissMs}ms")
    }

    private fun resetAutoDismissTimer(reason: String) {
        lastInteractionTs = System.currentTimeMillis()
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = Runnable {
            val elapsed = System.currentTimeMillis() - lastInteractionTs
            if (elapsed >= autoDismissMs) {
                android.util.Log.d(TAG, "Auto dismiss triggered (reason reset path) after ${elapsed}ms")
                hideOverlay()
            }
        }
        mainHandler.postDelayed(autoDismissRunnable!!, autoDismissMs)
        android.util.Log.d(TAG, "Auto dismiss timer reset due to $reason")
    }
}
