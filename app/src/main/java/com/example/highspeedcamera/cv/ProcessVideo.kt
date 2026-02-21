package com.example.highspeedcamera.cv

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.Videoio
import java.util.LinkedList

enum class FrameStatus { NORMAL, FRAME_DROP, FRAME_MERGE }
enum class DropReason { NONE, HIGH_SPIKE, FRAME_FREEZE }

data class FrameReport(
    val timestampMs: Double,
    val status: FrameStatus,
    val dropReason: DropReason,
    val motionValue: Double
)

private class FrameState {
    var img = Mat()
    var timestamp = 0.0
    fun release() { if (!img.empty()) img.release() }
}

fun calculateMotion(prev: Mat, curr: Mat): Double {
    val blur1 = Mat()
    val blur2 = Mat()
    Imgproc.GaussianBlur(prev, blur1, Size(5.0, 5.0), 0.0)
    Imgproc.GaussianBlur(curr, blur2, Size(5.0, 5.0), 0.0)

    val diff = Mat()
    Core.absdiff(blur1, blur2, diff)

    val binary = Mat()
    Imgproc.threshold(diff, binary, 10.0, 255.0, Imgproc.THRESH_BINARY)

    val motionPixels = Core.countNonZero(binary).toDouble()

    blur1.release()
    blur2.release()
    diff.release()
    binary.release()

    return motionPixels
}

fun processVideo(videoPath: String): List<FrameReport> {

    val capture = VideoCapture(videoPath)
    if (!capture.isOpened) return emptyList()

    val fps = capture.get(Videoio.CAP_PROP_FPS).let { if (it <= 0) 30.0 else it }
    val interval = 1000.0 / fps

    val motionWindow = LinkedList<Double>()
    val reportList = mutableListOf<FrameReport>()

    var f1 = FrameState()
    var f2 = FrameState()
    var f3 = FrameState()

    var frameIdx = 0

    fun prepare(target: FrameState): Boolean {
        val tmp = Mat()
        if (capture.read(tmp)) {
            Imgproc.cvtColor(tmp, target.img, Imgproc.COLOR_BGR2GRAY)
            target.timestamp = frameIdx * interval
            tmp.release()
            return true
        }
        tmp.release()
        return false
    }

    if (prepare(f1)) frameIdx++
    if (prepare(f2)) frameIdx++

    while (prepare(f3)) {

        val motion = calculateMotion(f1.img, f2.img)

        if (motionWindow.size >= 7)
            motionWindow.removeFirst()

        val localMedian =
            if (motionWindow.isNotEmpty())
                motionWindow.sorted()[motionWindow.size / 2]
            else motion

        val valleyFactor = 0.35
        val isFreeze =
            motionWindow.size >= 3 &&
                    motion < localMedian * valleyFactor &&
                    motion < motionWindow.last()

        val spikeFactor = 1.5
        val isDrop = motion > localMedian * spikeFactor

        motionWindow.add(motion)

        var status = FrameStatus.NORMAL
        var reason = DropReason.NONE

        when {
            isFreeze -> {
                status = FrameStatus.FRAME_MERGE
                reason = DropReason.FRAME_FREEZE
            }
            isDrop -> {
                status = FrameStatus.FRAME_DROP
                reason = DropReason.HIGH_SPIKE
            }
        }

        reportList.add(
            FrameReport(
                f2.timestamp,
                status,
                reason,
                motion
            )
        )

        f1.release()
        f1 = f2
        f2 = f3
        f3 = FrameState()

        frameIdx++
    }

    capture.release()
    f1.release()
    f2.release()
    f3.release()

    if (reportList.isNotEmpty()) {
        reportList.removeAt(reportList.lastIndex)
    }
    return reportList
}