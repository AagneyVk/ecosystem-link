package com.ecosystem.agent.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ScreenCaptureActivity : Activity() {
    companion object { const val EXTRA_SESSION_ID="session_id"; const val EXTRA_DURATION="duration"; const val EXTRA_QUALITY="quality"; const val EXTRA_MODE="mode"; private const val REQUEST_CAPTURE=501 }
    override fun onCreate(state: Bundle?) { super.onCreate(state); if (state == null) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }}
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            val live = intent.getStringExtra(EXTRA_MODE) == "live"
            val service = Intent(this, if (live) ScreenLiveService::class.java else ScreenRecordingService::class.java).apply {
                action = if (live) ScreenLiveService.ACTION_START else ScreenRecordingService.ACTION_START
                putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_SESSION_ID, intent.getStringExtra(EXTRA_SESSION_ID)); putExtra(EXTRA_DURATION, intent.getIntExtra(EXTRA_DURATION,30)); putExtra(EXTRA_QUALITY, intent.getStringExtra(EXTRA_QUALITY))
            }
            startForegroundService(service)
        }
        finish()
    }
}
