package com.campusmarket.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.campusmarket.app.platform.DeepLinks

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold start from campusmarket://… (not on recreation, or we'd re-handle the same link after rotation).
        if (savedInstanceState == null) DeepLinks.handle(intent?.dataString)
        setContent {
            App()
        }
    }

    /** singleTask: returning from Stripe Checkout / onboarding in the browser lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.handle(intent.dataString)
    }
}
