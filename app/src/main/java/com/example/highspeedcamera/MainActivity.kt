package com.example.highspeedcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.highspeedcamera.ui.HighSpeedCameraApp
import com.example.highspeedcamera.ui.theme.HighSpeedCameraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_HighSpeedCamera)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HighSpeedCameraTheme {
                HighSpeedCameraApp()
            }
        }
    }
}
