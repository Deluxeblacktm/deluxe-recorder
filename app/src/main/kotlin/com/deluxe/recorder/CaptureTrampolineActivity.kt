package com.deluxe.recorder

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

class CaptureTrampolineActivity : Activity() {

    companion object {
        private const val TAG = "CaptureTrampoline"
        private const val REQUEST_CAPTURE = 2026
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        Log.d(TAG, "Activity launched")

        if (RecordingState.projectionResultCode != 0 && RecordingState.projectionData != null) {
            Log.d(TAG, "Projection already available, capturing after delay")
            scheduleCapture()
        } else {
            Log.d(TAG, "No projection, requesting permission")
            requestProjectionPermission()
        }
    }

    private fun scheduleCapture() {
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Delay elapsed, firing ACTION_SCREENSHOT")
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_SCREENSHOT
                putExtra(RecordingService.EXTRA_RESULT_CODE, RecordingState.projectionResultCode)
                putExtra(RecordingService.EXTRA_DATA, RecordingState.projectionData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            finish()
        }, 400)
    }

    @Suppress("DEPRECATION")
    private fun requestProjectionPermission() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "Permission granted, scheduling capture")
                RecordingState.setProjectionData(resultCode, data)
                scheduleCapture()
            } else {
                Log.d(TAG, "Permission denied")
                finish()
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
