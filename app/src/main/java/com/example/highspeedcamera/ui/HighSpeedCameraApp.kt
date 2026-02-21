package com.example.highspeedcamera.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.highspeedcamera.camera.HighSpeedCameraViewModel
import com.example.highspeedcamera.ui.screens.ConfigScreen
import com.example.highspeedcamera.ui.screens.PermissionsScreen
import com.example.highspeedcamera.ui.screens.RecordScreen


@Composable
fun HighSpeedCameraApp() {
    val navController = rememberNavController()
    val configVm: ConfigViewModel      = viewModel()
    val cameraVm: HighSpeedCameraViewModel = viewModel()

    NavHost(navController = navController, startDestination = "permissions") {

        composable("permissions") {
            PermissionsScreen(
                onAllGranted = {
                    navController.navigate("config") {
                        popUpTo("permissions") { inclusive = true }
                    }
                }
            )
        }

        composable("config") {
            ConfigScreen(
                viewModel = configVm,
                onStart   = { navController.navigate("record") }
            )
        }

        composable("record") {
            RecordScreen(
                configVm = configVm,
                cameraVm = cameraVm,
                onBack   = { navController.popBackStack() }
            )
        }
    }
}
