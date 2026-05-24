package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ProjectionPermissionActivity : Activity() {
    companion object {
        const val ACTION_PROJECTION_AUTHORIZED = "com.example.ACTION_PROJECTION_AUTHORIZED"
        
        fun getStartIntent(context: Context): Intent {
            return Intent(context, ProjectionPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), 101)
        } catch (e: Exception) {
            OverlayService.setProjectionResult(Activity.RESULT_CANCELED, null)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 101) {
            if (resultCode == RESULT_OK && data != null) {
                // Store results for OverlayService usage
                OverlayService.setProjectionResult(resultCode, data)
                // Notify the service or UI that authorization succeeded
                sendBroadcast(Intent(ACTION_PROJECTION_AUTHORIZED))
            } else {
                OverlayService.setProjectionResult(Activity.RESULT_CANCELED, null)
            }
        }
        finish()
    }
}
