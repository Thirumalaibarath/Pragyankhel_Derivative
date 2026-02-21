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

enum class FrameStatus { NORMAL, FRAME_DROP, FRAME_MERGE }
enum class DropReason { NONE, DELTA_TIME, HIGH_PSNR, LOW_MEAN_THRESHOLD }

data class FrameReport(
    val timestampMs: Double,
    val status: FrameStatus,
    val dropReason: DropReason,
    val sharpness: Double,
    val motionMean: Double
)

data class FrameStats(val mean: Double, val stdDev: Double, val psnr: Double)
data class NoiseBaseline(val mu: Double, val sigma: Double)

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

fun calculateSharpness(frame: Mat): Double {
    val dest = Mat()
    val m = MatOfDouble()
    val s = MatOfDouble()

    Imgproc.Laplacian(frame, dest, CvType.CV_64F)
    Core.meanStdDev(dest, m, s)
    val variance = s.get(0, 0)[0].pow(2.0)

    dest.release()
    m.release()
    s.release()

    return variance
}

fun calibrateNoiseFloor(videoPath: String, sampleFrames: Int = 15): NoiseBaseline {
    val capture = VideoCapture(videoPath)
    val prev = Mat()
    val curr = Mat()
    val muSamples = mutableListOf<Double>()
    val sigmaSamples = mutableListOf<Double>()
    var count = 0

    while (count < sampleFrames && capture.read(curr)) {
        if (curr.empty()) break

        val gray = Mat()
        when (curr.channels()) {
            3 -> Imgproc.cvtColor(curr, gray, Imgproc.COLOR_BGR2GRAY)
            4 -> Imgproc.cvtColor(curr, gray, Imgproc.COLOR_BGRA2GRAY)
            else -> curr.copyTo(gray)
        }
        Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)

        if (!prev.empty()) {
            val stats = calculateStats(prev, gray)
            if (stats.mean < 1.0) {
                muSamples.add(stats.mean)
                sigmaSamples.add(stats.stdDev)
            }
        }
        gray.copyTo(prev)
        gray.release()
        count++
    }

    capture.release()
    prev.release()
    curr.release()

    val avgMu = if (muSamples.isNotEmpty()) muSamples.average() else 0.05
    val avgSigma = if (sigmaSamples.isNotEmpty()) sigmaSamples.average() else 0.02

    return NoiseBaseline(avgMu, avgSigma)
}

fun processVideo(videoPath: String): List<FrameReport> {
    val baseline = calibrateNoiseFloor(videoPath)
    val reportList = mutableListOf<FrameReport>()
    val capture = VideoCapture(videoPath)

    if (!capture.isOpened) return reportList

    val fps = capture.get(Videoio.CAP_PROP_FPS)
    val interval = 1000.0 / fps

    val prevFrame = FrameState()
    val currFrame = FrameState()
    val nextFrame = FrameState()

    fun prepareFrame(sourceMat: Mat, targetState: FrameState, timestamp: Double) {
        if (sourceMat.empty()) return
        when (sourceMat.channels()) {
            3 -> Imgproc.cvtColor(sourceMat, targetState.img, Imgproc.COLOR_BGR2GRAY)
            4 -> Imgproc.cvtColor(sourceMat, targetState.img, Imgproc.COLOR_BGRA2GRAY)
            else -> sourceMat.copyTo(targetState.img)
        }
        Imgproc.GaussianBlur(targetState.img, targetState.img, Size(3.0, 3.0), 0.0)
        targetState.sharpness = calculateSharpness(targetState.img)
        targetState.timestamp = timestamp
    }

    if (capture.read(currFrame.img)) {
        prepareFrame(currFrame.img, currFrame, capture.get(Videoio.CAP_PROP_POS_MSEC))
    }

    while (capture.read(nextFrame.img)) {
        val ts = capture.get(Videoio.CAP_PROP_POS_MSEC)
        prepareFrame(nextFrame.img, nextFrame, ts)

        if (!prevFrame.img.empty() && !currFrame.img.empty()) {
            val delta = currFrame.timestamp - prevFrame.timestamp
            val stats = calculateStats(prevFrame.img, currFrame.img)

            var status = FrameStatus.NORMAL
            var reason = DropReason.NONE

            val dynamicThreshold = baseline.mu + (2.0 * stats.stdDev)

            if (delta > (interval * 1.5)) {
                status = FrameStatus.FRAME_DROP
                reason = DropReason.DELTA_TIME
            } else if (stats.psnr > 45.0) {
                status = FrameStatus.FRAME_DROP
                reason = DropReason.HIGH_PSNR
            } else if (stats.mean < dynamicThreshold) {
                status = FrameStatus.FRAME_DROP
                reason = DropReason.LOW_MEAN_THRESHOLD
            } else if (stats.mean > 1.5 &&
                currFrame.sharpness < (prevFrame.sharpness * 0.4) &&
                currFrame.sharpness < (nextFrame.sharpness * 0.4)) {
                status = FrameStatus.FRAME_MERGE
            }

            reportList.add(FrameReport(currFrame.timestamp, status, reason, currFrame.sharpness, stats.mean))
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