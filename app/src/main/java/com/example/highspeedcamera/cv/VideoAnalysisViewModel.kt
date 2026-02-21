package com.example.highspeedcamera.cv

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class AnalysisState {
    object Idle : AnalysisState()
    object Processing : AnalysisState()
    data class Done(val results: List<FrameResult>) : AnalysisState()
    data class Error(val message: String) : AnalysisState()
}

data class FrameResult(
    val index: Int,
    val timestampMs: Double,
    val motionValue: Double,
    val status: FrameStatus,
    val dropReason: DropReason = DropReason.NONE
)

data class AnalysisSummary(
    val total: Int,
    val normal: Int,
    val drops: Int,
    val merges: Int
)

fun List<FrameResult>.summary() = AnalysisSummary(
    total = size,
    normal = count { it.status == FrameStatus.NORMAL },
    drops = count { it.status == FrameStatus.FRAME_DROP },
    merges = count { it.status == FrameStatus.FRAME_MERGE }
)

class VideoAnalysisViewModel : ViewModel() {

    private val _state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val state: StateFlow<AnalysisState> = _state

    fun analyse(context: Context, uri: Uri) {
        _state.value = AnalysisState.Processing

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempFile = copyUriToCache(context, uri)
                val rawFrames = processVideo(tempFile.absolutePath)

                val results = rawFrames.mapIndexed { i, fd ->
                    FrameResult(
                        index = i,
                        timestampMs = fd.timestampMs,
                        motionValue = fd.motionValue,
                        status = fd.status,
                        dropReason = fd.dropReason
                    )
                }

                tempFile.delete()
                _state.value = AnalysisState.Done(results)

            } catch (e: Exception) {
                _state.value = AnalysisState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() { _state.value = AnalysisState.Idle }

    private fun copyUriToCache(context: Context, uri: Uri): File {
        val dest = File(context.cacheDir, "analysis_input.mp4")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }
}