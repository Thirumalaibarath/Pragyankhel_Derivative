package com.example.highspeedcamera.cv

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
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

// Mean and Standard Deviation
data class FrameStats(val mean: Double, val stdDev: Double, val psnr: Double)
data class FrameData(val img: Mat, var sharpness: Double = 0.0, var timestamp: Double = 0.0, var status: FrameStatus = FrameStatus.NORMAL)

fun calculateStats(prev: Mat, curr: Mat) : FrameStats {
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
    val mse = sse / (prev.total().toDouble())
    val psnrVal = if (mse <= 1e-9) Double.POSITIVE_INFINITY else 10.0 * log10((255.0 * 255.0) / mse)

    diffMap.release()
    mean.release()
    stdDev.release()
    sseMat.release()

    return FrameStats(mu, sigma, psnrVal)
}

fun isFrameDrop(prev: Mat, curr: Mat) : Boolean {
    val stats = calculateStats(prev, curr)
    val threshold = stats.mean + (2.5 * stats.stdDev)
    return stats.psnr > 60.0 || stats.mean < threshold
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

fun processVideo(videoPath: String) : MutableList<FrameData> {
    val frameList = mutableListOf<FrameData>()
    val capture = VideoCapture(videoPath)

    if (!capture.isOpened) return frameList

    val fps = capture.get(Videoio.CAP_PROP_FPS)
    val interval = 1000.0 / fps // DeltaT

    val prevFrame = FrameData(Mat())
    val currFrame = FrameData(Mat())
    val nextFrame = FrameData(Mat())

    // Convert to GrayScale for Faster Calculations
    if (capture.read(currFrame.img)) {
        Imgproc.cvtColor(currFrame.img, currFrame.img, Imgproc.COLOR_BGR2GRAY)
        currFrame.sharpness = calculateSharpness(currFrame.img)
        currFrame.timestamp = capture.get(Videoio.CAP_PROP_POS_MSEC)
    }

    while (capture.read(nextFrame.img)) {
        val currentMs = capture.get(Videoio.CAP_PROP_POS_MSEC)
        Imgproc.cvtColor(nextFrame.img, nextFrame.img, Imgproc.COLOR_BGR2GRAY)
        nextFrame.sharpness = calculateSharpness(nextFrame.img)
        nextFrame.timestamp = currentMs

        if (!prevFrame.img.empty()) {
            val delta = currFrame.timestamp - prevFrame.timestamp // Time Interval
            currFrame.status = when {
                delta > (interval * 1.5) -> FrameStatus.FRAME_DROP
                isFrameDrop(prevFrame.img, currFrame.img) -> FrameStatus.FRAME_DROP
                currFrame.sharpness < prevFrame.sharpness * 0.35 && currFrame.sharpness < nextFrame.sharpness * 0.35 -> FrameStatus.FRAME_MERGE
                else -> FrameStatus.NORMAL
            }

            frameList.add(FrameData(currFrame.img.clone(), currFrame.sharpness, currFrame.timestamp, currFrame.status))
        }

        currFrame.img.copyTo(prevFrame.img)
        prevFrame.sharpness = currFrame.sharpness
        prevFrame.timestamp = currFrame.timestamp

        nextFrame.img.copyTo(currFrame.img)
        currFrame.sharpness = nextFrame.sharpness
        currFrame.timestamp = nextFrame.timestamp
    }

    // Release Matrices
    capture.release()
    prevFrame.img.release()
    currFrame.img.release()
    nextFrame.img.release()

    return frameList
}