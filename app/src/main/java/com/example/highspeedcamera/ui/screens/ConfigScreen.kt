package com.example.highspeedcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.example.highspeedcamera.ui.ConfigViewModel
import com.example.highspeedcamera.utils.VideoCodec
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Shared slider colour tokens
private val SliderThumbColor        = Color(0xFF444444)   // dark grey thumb
private val SliderActiveTrack       = Color.White
private val SliderInactiveTrack     = Color.White.copy(alpha = 0.30f)
private val SliderActiveTickColor   = Color.Transparent
private val SliderInactiveTickColor = Color.Transparent

@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel,
    onStart: () -> Unit
) {
    val cameras      by viewModel.cameraOptions.collectAsState()
    val selectedCamera  by viewModel.selectedCamera.collectAsState()
    val selectedDr      by viewModel.selectedDynamicRange.collectAsState()
    val selectedCodec   by viewModel.selectedCodecId.collectAsState()
    val noPreview       by viewModel.noPreview.collectAsState()
    val iso             by viewModel.selectedIso.collectAsState()
    val shutterNs       by viewModel.selectedShutterNs.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF6931FF))
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("High Speed Camera", style = MaterialTheme.typography.headlineMedium)
        }

        item {
            SectionHeader("Camera / Resolution / FPS")
            if (cameras.isEmpty()) {
                Text("No high-speed cameras found on this device.",
                    color = MaterialTheme.colorScheme.error)
            } else {
                cameras.forEach { option ->
                    RadioRow(
                        label   = option.label,
                        selected = selectedCamera == option,
                        onClick  = {
                            viewModel.selectedCamera.value = option
                            viewModel.selectedDynamicRange.value = option.supportedDynamicRanges.first()
                            // Reset ISO to midpoint and shutter to 1/fps for new camera
                            viewModel.selectedIso.value = ((option.isoRange.lower + option.isoRange.upper) / 2)
                            viewModel.selectedShutterNs.value = 1_000_000_000L / option.fps
                        }
                    )
                }
            }
        }

        item {
            val camOption = selectedCamera
            val isoMin = camOption?.isoRange?.lower ?: 100
            val isoMax = camOption?.isoRange?.upper ?: 3200
            SectionHeader("ISO")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ISO $iso", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(80.dp))
                Slider(
                    value = iso.toFloat(),
                    onValueChange = { viewModel.selectedIso.value = it.roundToInt() },
                    valueRange = isoMin.toFloat()..isoMax.toFloat(),
                    steps = ((isoMax - isoMin) / 50).coerceAtLeast(0),
                    colors = SliderDefaults.colors(
                        thumbColor           = SliderThumbColor,
                        activeTrackColor     = SliderActiveTrack,
                        inactiveTrackColor   = SliderInactiveTrack,
                        activeTickColor      = SliderActiveTickColor,
                        inactiveTickColor    = SliderInactiveTickColor
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text  = "Range: ISO $isoMin – $isoMax",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            val camOption   = selectedCamera
            val maxShutterNs = camOption?.let { 1_000_000_000L / it.fps } ?: 8_333_333L
            val minShutterNs = camOption?.minShutterNs ?: 1_000_000L
            SectionHeader("Shutter Speed")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text     = formatShutter(shutterNs),
                    style    = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(90.dp)
                )
                Slider(
                    value = shutterNs.toFloat(),
                    onValueChange = { viewModel.selectedShutterNs.value = it.roundToLong() },
                    valueRange = minShutterNs.toFloat()..maxShutterNs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor           = SliderThumbColor,
                        activeTrackColor     = SliderActiveTrack,
                        inactiveTrackColor   = SliderInactiveTrack,
                        activeTickColor      = SliderActiveTickColor,
                        inactiveTickColor    = SliderInactiveTickColor
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text  = "Max = 1/${camOption?.fps ?: 120} s  (frame duration cap)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            SectionHeader("Dynamic Range")
            val ranges = selectedCamera?.supportedDynamicRanges ?: emptyList()
            if (ranges.isEmpty()) {
                Text("Select a camera first.")
            } else {
                ranges.forEach { dr ->
                    RadioRow(
                        label    = dr.label,
                        selected = selectedDr == dr,
                        onClick  = { viewModel.selectedDynamicRange.value = dr }
                    )
                }
            }
        }

        item {
            SectionHeader("Video Codec")
            val codecs = viewModel.availableCodecs()
            if (codecs.isEmpty()) {
                Text("No compatible encoder found.", color = MaterialTheme.colorScheme.error)
            } else {
                codecs.forEach { codec ->
                    val codecId = when (codec) {
                        VideoCodec.HEVC -> VideoCodec.ID_HEVC
                        VideoCodec.H264 -> VideoCodec.ID_H264
                        VideoCodec.AV1  -> VideoCodec.ID_AV1
                    }
                    RadioRow(
                        label    = codec.displayName,
                        selected = selectedCodec == codecId,
                        onClick  = { viewModel.selectedCodecId.value = codecId }
                    )
                }
            }
        }

        item {
            SectionHeader("Preview")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text     = if (noPreview) "Preview OFF — encoder only (better fps stability)"
                               else            "Preview ON",
                    modifier = Modifier.weight(1f),
                    style    = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked         = !noPreview,
                    onCheckedChange = { viewModel.noPreview.value = !it }
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick  = onStart,
                enabled  = selectedCamera != null,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("START RECORDING", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

fun formatShutter(ns: Long): String {
    if (ns <= 0L) return "—"
    val denom = (1_000_000_000.0 / ns).roundToInt()
    return "1/${denom}s"
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.titleSmall,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick  = onClick,
            colors   = RadioButtonDefaults.colors(
                selectedColor   = Color.Black,
                unselectedColor = Color.White
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
