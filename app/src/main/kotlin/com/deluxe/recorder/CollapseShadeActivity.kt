package com.deluxe.recorder

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class CollapseShadeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun createIntent(activity: Activity): Intent =
            Intent(activity, CollapseShadeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }

        fun createIntentFromService(service: android.app.Service): Intent =
            Intent(service, CollapseShadeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
    }
}
