package com.example.highspeedcamera.cv

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.videoio.VideoCapture
import org.opencv.videoio.Videoio
import kotlin.math.log10
import kotlin.math.pow

enum class FrameStatus {
    NORMAL,
    FRAME_DROP,
    FRAME_MERGE
}

// Added the diagnostic enum
enum class DropReason {
    NONE,
    DELTA_TIME,
    HIGH_PSNR,
    LOW_MEAN_THRESHOLD
}

// Added dropReason to the report
data class FrameReport(
    val timestampMs: Double,
    val status: FrameStatus,
    val dropReason: DropReason,
    val sharpness: Double,
    val motionMean: Double
)

data class FrameStats(val mean: Double, val stdDev: Double, val psnr: Double)

private class FrameState {
    val img = Mat()
    var sharpness: Double = 0.0
    var timestamp: Double = 0.0
}

fun calculateStats(prev: Mat, curr: Mat): FrameStats {
    val diffMap = Mat()
    val mean = MatOfDouble()
    val stdDev = MatOfDouble()

    Core.absdiff(prev, curr, diffMap)
    Core.meanStdDev(diffMap, mean, stdDev)

    val mu = mean.get(0, 0)[0]
    val sigma = stdDev.get(0, 0)[0]

    val sseMat = Mat()
    diffMap.convertTo(sseMat, CvType.CV_32F)
    Core.multiply(sseMat, sseMat, sseMat)

    val sse = Core.sumElems(sseMat).`val`[0]
    val mse = sse / prev.total().toDouble()

    val psnrVal = if (mse <= 1e-9) Double.POSITIVE_INFINITY else 10.0 * log10((255.0 * 255.0) / mse)

    diffMap.release()
    mean.release()
    stdDev.release()
    sseMat.release()

    return FrameStats(mu, sigma, psnrVal)
}

// Refactored to return the exact reason instead of a boolean
fun getDropReason(stats: FrameStats): DropReason {
    if (stats.psnr > 50.0) return DropReason.HIGH_PSNR

    val isStatic = stats.mean < (0.25 * stats.stdDev)
    val isNegligible = stats.mean < 0.08

    if (isStatic || isNegligible) return DropReason.LOW_MEAN_THRESHOLD

    return DropReason.NONE
}

fun calculateSharpness(frame: Mat): Double {
    val destination = Mat()
    val mean = MatOfDouble()
    val stdDev = MatOfDouble()

    Imgproc.Laplacian(frame, destination, CvType.CV_64F)

    Core.meanStdDev(destination, mean, stdDev)
    val variance = stdDev.get(0, 0)[0].pow(2.0)

    destination.release()
    mean.release()
    stdDev.release()

    return variance
}

fun processVideo(videoPath: String): List<FrameReport> {
    val reportList = mutableListOf<FrameReport>()
    val capture = VideoCapture(videoPath)

    if (!capture.isOpened) return reportList

    val fps = capture.get(Videoio.CAP_PROP_FPS)
    val interval = 1000.0 / fps // Expected Delta T in ms

    val prevFrame = FrameState()
    val currFrame = FrameState()
    val nextFrame = FrameState()

    fun prepareFrame(sourceMat: Mat, targetState: FrameState, timestamp: Double) {
//        Imgproc.cvtColor(sourceMat, targetState.img, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(targetState.img, targetState.img, Size(3.0, 3.0), 0.0)

        targetState.sharpness = calculateSharpness(targetState.img)
        targetState.timestamp = timestamp
    }

    if (capture.read(currFrame.img)) {
        val ts = capture.get(Videoio.CAP_PROP_POS_MSEC)
        prepareFrame(currFrame.img, currFrame, ts)
    }

    while (capture.read(nextFrame.img)) {
        val currentMs = capture.get(Videoio.CAP_PROP_POS_MSEC)
        prepareFrame(nextFrame.img, nextFrame, currentMs)

        if (!prevFrame.img.empty()) {
            val delta = currFrame.timestamp - prevFrame.timestamp
            val stats = calculateStats(prevFrame.img, currFrame.img)

            // Evaluate drop reason
            val visualDropReason = getDropReason(stats)

            var currentStatus = FrameStatus.NORMAL
            var currentDropReason = DropReason.NONE

            // Hierarchical classification
            if (delta > (interval * 1.5)) {
                currentStatus = FrameStatus.FRAME_DROP
                currentDropReason = DropReason.DELTA_TIME
            } else if (visualDropReason != DropReason.NONE) {
                currentStatus = FrameStatus.FRAME_DROP
                currentDropReason = visualDropReason
            } else if (stats.mean > 1.5 &&
                currFrame.sharpness < (prevFrame.sharpness * 0.4) &&
                currFrame.sharpness < (nextFrame.sharpness * 0.4)) {
                currentStatus = FrameStatus.FRAME_MERGE
            }

            reportList.add(FrameReport(
                timestampMs = currFrame.timestamp,
                status = currentStatus,
                dropReason = currentDropReason,
                sharpness = currFrame.sharpness,
                motionMean = stats.mean
            ))
        }

        currFrame.img.copyTo(prevFrame.img)
        prevFrame.sharpness = currFrame.sharpness
        prevFrame.timestamp = currFrame.timestamp

        nextFrame.img.copyTo(currFrame.img)
        currFrame.sharpness = nextFrame.sharpness
        currFrame.timestamp = nextFrame.timestamp
    }

    capture.release()
    prevFrame.img.release()
    currFrame.img.release()
    nextFrame.img.release()

    return reportList
}