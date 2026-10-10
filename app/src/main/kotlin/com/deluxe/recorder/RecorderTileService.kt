package com.deluxe.recorder

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class RecorderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onStopListening() {
        super.onStopListening()
    }

    override fun onDestroy() {
        unlockAndRun {
            Log.d("RecorderTileService", "Cleaning up tile binder")
        }
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        Log.d("RecorderTileService", "Tile clicked, isRecording=${RecordingState.isRecording}")

        if (RecordingState.isRecording) {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP_RECORDING
            }
            startService(intent)
        } else {
            val idleIntent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_INIT_IDLE
            }
            startService(idleIntent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        if (RecordingState.isRecording) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = getString(R.string.tile_label_recording)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.app_name)
        }
        tile.updateTile()
    }
}
