package com.bikecouscous.reebok.health

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Shown when the user taps the privacy-policy link on the Health Connect
 * permission screen. Required boilerplate for a well-formed Health Connect
 * integration; this app is sideloaded and used by a single person, so it's
 * just a short static explanation rather than a real policy document.
 */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                text = "Bike Couscous reads live metrics from a Reebok SL8.0 exercise " +
                    "bike over Bluetooth and writes each completed workout (heart rate, " +
                    "speed, cadence, distance, and calories) to Health Connect. Nothing " +
                    "leaves this device."
            },
        )
    }
}
