package com.deluxe.recorder

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

class TransparentPermissionActivity : Activity() {

    companion object {
        private const val REQUEST_SCREENSHOT = 1001
    }

    private var takeScreenshotAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        takeScreenshotAfterPermission = intent?.getBooleanExtra("take_screenshot", false) ?: false

        if (RecordingState.projectionResultCode != 0 && RecordingState.projectionData != null) {
            startRecordingAndFinish()
            return
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREENSHOT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREENSHOT && resultCode == RESULT_OK && data != null) {
            RecordingState.setProjectionData(resultCode, data)
        }
        startRecordingAndFinish()
    }

    private fun startRecordingAndFinish() {
        if (RecordingState.projectionResultCode != 0 && RecordingState.projectionData != null) {
            if (RecordingState.isRecording) {
                finish()
                return
            }

            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START_RECORDING
                putExtra(RecordingService.EXTRA_RESULT_CODE, RecordingState.projectionResultCode)
                putExtra(RecordingService.EXTRA_DATA, RecordingState.projectionData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            if (takeScreenshotAfterPermission) {
                val screenshotIntent = Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_SCREENSHOT
                }
                startService(screenshotIntent)
            }
        }

        finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
