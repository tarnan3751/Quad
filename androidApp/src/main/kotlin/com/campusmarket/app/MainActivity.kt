package com.campusmarket.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.campusmarket.app.platform.DeepLinks
import com.campusmarket.app.platform.startsOnBrandHero

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (startsOnBrandHero()) {
            // Dark icons from the first frame, in both themes (the hero's sky is amber).
            enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        } else {
            enableEdgeToEdge()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The status bar takes this window's icon colour only once the system splash window has gone, and then
            // fades to it: left alone, the first screen shows with the splash's icons for a moment (white on the
            // amber hero after dark). Taking over the splash view moves that change under the splash's last frames.
            splashScreen.setOnExitAnimationListener { splash ->
                splash.animate().alpha(0f).setStartDelay(SPLASH_HOLD_MS).setDuration(SPLASH_FADE_MS).withEndAction { splash.remove() }.start()
            }
        }
        // Cold start from campusmarket://… (not on recreation, or we'd re-handle the same link after rotation).
        if (savedInstanceState == null) DeepLinks.handle(intent?.dataString)
        setContent {
            App()
        }
    }

    private companion object {
        const val SPLASH_HOLD_MS = 200L
        const val SPLASH_FADE_MS = 150L
    }

    /** singleTask: returning from Stripe Checkout / onboarding in the browser lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.handle(intent.dataString)
    }
}
