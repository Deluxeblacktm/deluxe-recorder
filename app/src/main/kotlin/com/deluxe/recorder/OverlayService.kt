package com.deluxe.recorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW_READY = "com.deluxe.recorder.SHOW_READY"
        const val ACTION_SHOW_RECORDING = "com.deluxe.recorder.SHOW_RECORDING_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.deluxe.recorder.HIDE_BUBBLE"
        const val ACTION_TOGGLE_BUBBLE = "com.deluxe.recorder.TOGGLE_BUBBLE"
        private const val TAG = "OverlayService"
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var currentX = -1
    private var currentY = -1

    private var timerTextView: TextView? = null
    private var recordButton: ImageButton? = null
    private var pauseButton: ImageButton? = null
    private var stopButton: ImageButton? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private var isPausedState = false
    private var isRecordingMode = false
    private var recordingStartTime = 0L
    private var totalPauseTimeMs = 0L
    private var pauseStartMs = 0L

    private val colorPaused = Color.parseColor("#FFEB3B")
    private val timerStringBuilder = StringBuilder(5)

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (RecordingState.isRecording) {
                if (!isPausedState) {
                    val elapsed = (((System.currentTimeMillis() - recordingStartTime - totalPauseTimeMs)).coerceAtLeast(0L) / 1000).toInt()
                    updateTimerDisplay(elapsed)
                }
            }
            timerHandler.postDelayed(this, 500)
        }
    }

    private val pauseListener: (Boolean) -> Unit = { paused ->
        isPausedState = paused
        if (paused) {
            pauseStartMs = System.currentTimeMillis()
            timerTextView?.setTextColor(colorPaused)
        } else {
            if (pauseStartMs > 0L) {
                totalPauseTimeMs += (System.currentTimeMillis() - pauseStartMs)
                pauseStartMs = 0L
            }
            timerTextView?.setTextColor(Color.WHITE)
        }
        updatePauseButton()
    }

    private val recordingListener: (Boolean) -> Unit = { recording ->
        if (!recording) {
            timerHandler.removeCallbacks(timerRunnable)
            isRecordingMode = false
            totalPauseTimeMs = 0L
            pauseStartMs = 0L
        }
    }

    private val orientationListener = object : android.content.ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            repositionOverlay()
        }
        override fun onLowMemory() {}
    }

    private fun getScreenBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Rect(0, 0, bounds.width(), bounds.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    private fun repositionOverlay() {
        val p = params ?: return
        val v = overlayView ?: return
        val bounds = getScreenBounds()
        val bubbleW = if (v.width > 0) v.width else dp160
        val bubbleH = if (v.height > 0) v.height else dp56
        val maxX = (bounds.width() - bubbleW).coerceAtLeast(0)
        val maxY = (bounds.height() - bubbleH).coerceAtLeast(0)
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(0, maxY)
        currentX = p.x
        currentY = p.y
        try { windowManager.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        RecordingState.addPauseListener(pauseListener)
        RecordingState.addListener(recordingListener)
        registerComponentCallbacks(orientationListener)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private val dp8 by lazy { dp(8) }
    private val dp4 by lazy { dp(4) }
    private val dp12 by lazy { dp(12) }
    private val dp14 by lazy { dp(14) }
    private val dp16 by lazy { dp(16) }
    private val dp24 by lazy { dp(24) }
    private val dp160 by lazy { dp(160) }
    private val dp56 by lazy { dp(56) }
    private val dp1 by lazy { dp(1) }
    private val dp2 by lazy { dp(2) }
    private val dp3 by lazy { dp(3) }
    private val dp6 by lazy { dp(6) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_READY -> {
                if (!Settings.canDrawOverlays(this)) return START_NOT_STICKY
                showReadyBubble()
            }
            ACTION_SHOW_RECORDING -> {
                if (!Settings.canDrawOverlays(this)) return START_NOT_STICKY
                showRecordingBubble()
            }
            ACTION_HIDE_BUBBLE -> {
                hideBubble()
            }
            ACTION_TOGGLE_BUBBLE -> {
                try {
                    @Suppress("DEPRECATION")
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Exception) {}
                if (!Settings.canDrawOverlays(this)) {
                    val pIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(pIntent)
                    return START_NOT_STICKY
                }
                if (overlayView != null) {
                    hideBubble()
                } else {
                    if (RecordingState.isRecording) {
                        showRecordingBubble()
                    } else {
                        showReadyBubble()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try { windowManager.removeViewImmediate(view) } catch (_: Exception) {}
        }
        overlayView = null
        timerTextView = null
        recordButton = null
        pauseButton = null
        stopButton = null
    }

    private fun hideBubble() {
        timerHandler.removeCallbacks(timerRunnable)
        removeOverlay()
    }

    private fun createBubbleLayout(): LinearLayout {
        return object : LinearLayout(this) {
            private var touchStartX = 0f
            private var touchStartY = 0f
            private var initialWinX = 0
            private var initialWinY = 0
            private var isDragging = false
            private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                val p = params ?: return super.onInterceptTouchEvent(ev)
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartX = ev.rawX
                        touchStartY = ev.rawY
                        initialWinX = p.x
                        initialWinY = p.y
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(ev.rawX - touchStartX)
                        val dy = Math.abs(ev.rawY - touchStartY)
                        if (dx > touchSlop || dy > touchSlop) {
                            isDragging = true
                            return true
                        }
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                val p = params ?: return super.onTouchEvent(ev)
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartX = ev.rawX
                        touchStartY = ev.rawY
                        initialWinX = p.x
                        initialWinY = p.y
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - touchStartX
                        val dy = ev.rawY - touchStartY
                        if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val bounds = getScreenBounds()
                            val bubbleW = if (width > 0) width else dp160
                            val bubbleH = if (height > 0) height else dp56
                            val maxX = (bounds.width() - bubbleW).coerceAtLeast(0)
                            val maxY = (bounds.height() - bubbleH).coerceAtLeast(0)
                            p.x = (initialWinX + dx.toInt()).coerceIn(0, maxX)
                            p.y = (initialWinY + dy.toInt()).coerceIn(0, maxY)
                            currentX = p.x
                            currentY = p.y
                            try { windowManager.updateViewLayout(this, p) } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        return true
                    }
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp8, dp4, dp8, dp4)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))
                cornerRadius = dp14.toFloat()
            }
        }
    }

    private fun showReadyBubble() {
        isRecordingMode = false

        val rootLayout = createBubbleLayout()

        timerTextView = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            paint.isFakeBoldText = true
            setPadding(dp2, dp2, dp6, dp2)
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp1, dp16).apply {
                marginStart = dp2; marginEnd = dp2
            }
            setBackgroundColor(Color.parseColor("#66FFFFFF"))
        }

        recordButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_record)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp24, dp24)
            setPadding(dp3, dp3, dp3, dp3)
            setOnClickListener { startRecordingClicked() }
        }

        rootLayout.addView(timerTextView)
        rootLayout.addView(divider)
        rootLayout.addView(recordButton)

        addOverlayView(rootLayout)
    }

    private fun showRecordingBubble() {
        isRecordingMode = true

        val rootLayout = createBubbleLayout()

        timerTextView = TextView(this).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            paint.isFakeBoldText = true
            setPadding(dp2, dp2, dp6, dp2)
        }

        val divider1 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp1, dp16).apply {
                marginStart = dp2; marginEnd = dp2
            }
            setBackgroundColor(Color.parseColor("#66FFFFFF"))
        }

        pauseButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_pause)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp24, dp24)
            setPadding(dp3, dp3, dp3, dp3)
            setOnClickListener { togglePause() }
        }

        val divider2 = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp1, dp16).apply {
                marginStart = dp2; marginEnd = dp2
            }
            setBackgroundColor(Color.parseColor("#66FFFFFF"))
        }

        stopButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_stop)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LinearLayout.LayoutParams(dp24, dp24)
            setPadding(dp3, dp3, dp3, dp3)
            setOnClickListener { stopRecording() }
        }

        rootLayout.addView(timerTextView)
        rootLayout.addView(divider1)
        rootLayout.addView(pauseButton)
        rootLayout.addView(divider2)
        rootLayout.addView(stopButton)

        addOverlayView(rootLayout)
        recordingStartTime = System.currentTimeMillis()
        totalPauseTimeMs = 0L
        pauseStartMs = 0L
        timerHandler.postDelayed(timerRunnable, 500)
        updateTimerDisplay()
    }

    private fun addOverlayView(view: View) {
        overlayView?.let {
            try { windowManager.removeViewImmediate(it) } catch (_: Exception) {}
        }
        overlayView = view

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val bounds = getScreenBounds()
        val bubbleWidth = dp160
        val bubbleHeight = dp56

        if (currentX < 0 || currentY < 0) {
            currentX = dp12
            currentY = dp(100)
        }

        val maxX = (bounds.width() - bubbleWidth).coerceAtLeast(0)
        val maxY = (bounds.height() - bubbleHeight).coerceAtLeast(0)
        val initX = currentX.coerceIn(0, maxX)
        val initY = currentY.coerceIn(0, maxY)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        view.alpha = 0f
        view.scaleX = 0.9f
        view.scaleY = 0.9f

        try {
            windowManager.addView(view, params)
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay: ${e.message}")
            overlayView = null
        }
    }

    private fun startRecordingClicked() {
        val intent = Intent(this, TransparentPermissionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        startActivity(intent)
    }

    private fun togglePause() {
        if (RecordingState.isPaused) sendAction(RecordingService.ACTION_RESUME_RECORDING)
        else sendAction(RecordingService.ACTION_PAUSE_RECORDING)
    }

    private fun stopRecording() { sendAction(RecordingService.ACTION_STOP_RECORDING) }

    private fun updateTimerDisplay(elapsed: Int = RecordingState.elapsedSeconds) {
        val minutes = elapsed / 60
        val seconds = elapsed % 60
        timerStringBuilder.setLength(0)
        if (minutes < 10) timerStringBuilder.append('0')
        timerStringBuilder.append(minutes)
        timerStringBuilder.append(':')
        if (seconds < 10) timerStringBuilder.append('0')
        timerStringBuilder.append(seconds)
        timerTextView?.text = timerStringBuilder.toString()
    }

    private fun updatePauseButton() {
        if (isPausedState) pauseButton?.setImageResource(R.drawable.ic_play)
        else pauseButton?.setImageResource(R.drawable.ic_pause)
    }

    private fun sendAction(action: String) {
        val intent = Intent(this, RecordingService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    override fun onDestroy() {
        timerHandler.removeCallbacks(timerRunnable)
        try { RecordingState.removePauseListener(pauseListener) } catch (_: Exception) {}
        try { RecordingState.removeListener(recordingListener) } catch (_: Exception) {}
        try { unregisterComponentCallbacks(orientationListener) } catch (_: Exception) {}
        overlayView?.let {
            try { windowManager.removeViewImmediate(it) } catch (_: Exception) {}
        }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
