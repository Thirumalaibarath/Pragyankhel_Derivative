package com.example.highspeedcamera.ui

import android.annotation.SuppressLint
import android.app.Application
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecList
import android.util.Log
import android.util.Range
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.highspeedcamera.utils.VideoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "ConfigViewModel"

data class CameraOption(
    val cameraId: String,
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val supportedDynamicRanges: List<DynamicRangeOption>,
    val isoRange: Range<Int>,
    val minShutterNs: Long
)

data class DynamicRangeOption(val label: String, val profile: Long)

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val _cameraOptions = MutableStateFlow<List<CameraOption>>(emptyList())
    val cameraOptions: StateFlow<List<CameraOption>> = _cameraOptions

    var selectedCamera      = MutableStateFlow<CameraOption?>(null)
    var selectedDynamicRange = MutableStateFlow(DynamicRangeOption("SDR", DynamicRangeProfiles.STANDARD))
    var selectedCodecId     = MutableStateFlow(VideoCodec.ID_HEVC)
    var noPreview           = MutableStateFlow(false)
    var selectedIso         = MutableStateFlow(400)
    var selectedShutterNs   = MutableStateFlow(1_000_000_000L / 120)

    init { loadCameras() }

    @SuppressLint("MissingPermission")
    private fun loadCameras() = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        val manager = app.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager

        val options = mutableListOf<CameraOption>()

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO))
                continue

            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK  -> "Back"
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                else -> "External"
            }
            val config = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            for (size in config.highSpeedVideoSizes) {
                for (range in config.getHighSpeedVideoFpsRangesFor(size)) {
                    val fps = range.upper
                    if (fps < 120) continue

                    val dynRanges  = enumerateDynamicRanges(chars)
                    val isoRange   = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                                      ?: Range(100, 3200)
                    val expRange   = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val minShutter = expRange?.lower ?: 1_000_000L

                    options += CameraOption(
                        cameraId  = id,
                        label     = "$facing  ${size.width}×${size.height}  @$fps fps",
                        width     = size.width,
                        height    = size.height,
                        fps       = fps,
                        supportedDynamicRanges = dynRanges,
                        isoRange   = isoRange,
                        minShutterNs = minShutter
                    )
                }
            }
        }

        val deduplicated = options.distinctBy { Triple(it.cameraId, it.fps, it.width to it.height) }

        Log.d(TAG, "Found ${deduplicated.size} high-speed camera options (${options.size} before dedup)")
        _cameraOptions.value = deduplicated
        if (selectedCamera.value == null) {
            selectedCamera.value = deduplicated.firstOrNull()
            deduplicated.firstOrNull()?.let { cam ->
                selectedIso.value       = cam.isoRange.lower.coerceAtLeast(100)
                    .let { lo -> ((lo + cam.isoRange.upper) / 2).coerceIn(lo, cam.isoRange.upper) }
                selectedShutterNs.value = 1_000_000_000L / cam.fps
            }
        }
    }

    private fun enumerateDynamicRanges(chars: CameraCharacteristics): List<DynamicRangeOption> {
        val list = mutableListOf(DynamicRangeOption("SDR", DynamicRangeProfiles.STANDARD))

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?: return list
            val supported = profiles.supportedProfiles
            val map = mapOf(
                DynamicRangeProfiles.HLG10      to "HLG10",
                DynamicRangeProfiles.HDR10      to "HDR10",
                DynamicRangeProfiles.HDR10_PLUS to "HDR10+"
            )
            for ((profile, label) in map) {
                if (supported.contains(profile)) list += DynamicRangeOption(label, profile)
            }
        }
        return list
    }

    fun availableCodecs(): List<VideoCodec> {
        val dr = selectedDynamicRange.value.profile
        val candidates = if (dr == DynamicRangeProfiles.STANDARD) {
            listOf(VideoCodec.ID_H264, VideoCodec.ID_HEVC)
        } else {
            listOf(VideoCodec.ID_HEVC, VideoCodec.ID_AV1)
        }
        val mediaCodecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return candidates.filter { id ->
            mediaCodecList.codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals(VideoCodec.idToMime(id), ignoreCase = true)
                }
            }
        }.map { VideoCodec.fromId(it) }
    }
}
