package com.deluxe.recorder

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class ScreenshotTrampoline : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        if (RecordingState.projectionResultCode == 0 || RecordingState.projectionData == null) {
            val permIntent = Intent(this, TransparentPermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra("take_screenshot", true)
            }
            startActivity(permIntent)
        } else {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_SCREENSHOT
            }
            startService(intent)
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
