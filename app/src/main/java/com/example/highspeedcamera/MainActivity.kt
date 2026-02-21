package com.example.highspeedcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.highspeedcamera.ui.HighSpeedCameraApp
import com.example.highspeedcamera.ui.theme.HighSpeedCameraTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HighSpeedCameraTheme {
                HighSpeedCameraApp()
            }
        }

        OpenCVLoader.initDebug()
    }
}
