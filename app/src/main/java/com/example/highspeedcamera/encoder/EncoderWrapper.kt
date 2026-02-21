package com.example.highspeedcamera.encoder

import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Process
import android.util.Log
import android.view.Surface
import com.example.highspeedcamera.utils.VideoCodec
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

class EncoderWrapper(
    width: Int,
    height: Int,
    bitRate: Int,
    frameRate: Int,
    dynamicRange: Long,
    orientationHint: Int,
    outputFile: File,
    useMediaRecorder: Boolean,
    videoCodecId: Int,
    isHighSpeed: Boolean = false
) {
    companion object {
        const val TAG = "EncoderWrapper"
        const val VERBOSE = false
        const val IFRAME_INTERVAL = 1
        const val IFRAME_INTERVAL_HIGH_SPEED = 3
    }

    private val mWidth = width
    private val mHeight = height
    private val mBitRate = bitRate
    private val mFrameRate = frameRate
    private val mDynamicRange = dynamicRange
    private val mOrientationHint = orientationHint
    private val mOutputFile = outputFile
    private val mUseMediaRecorder = useMediaRecorder
    private val mVideoCodecId = videoCodecId
    private val mIsHighSpeed = isHighSpeed
    private val mMimeType = VideoCodec.idToMime(videoCodecId)

    private val mEncoderThread: EncoderThread? by lazy {
        if (useMediaRecorder) null
        else EncoderThread(mEncoder!!, outputFile, mOrientationHint, mIsHighSpeed)
    }

    private val mEncoder: MediaCodec? by lazy {
        if (useMediaRecorder) null
        else MediaCodec.createEncoderByType(mMimeType)
    }

    private val mInputSurface: Surface by lazy {
        if (useMediaRecorder) {
            val surface = MediaCodec.createPersistentInputSurface()
            createRecorder(surface).apply { prepare(); release() }
            surface
        } else {
            mEncoder!!.createInputSurface()
        }
    }

    private var mMediaRecorder: MediaRecorder? = null

    private fun createRecorder(surface: Surface): MediaRecorder =
        MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(mOutputFile.absolutePath)
            setVideoEncodingBitRate(mBitRate)
            if (mFrameRate > 0) setVideoFrameRate(mFrameRate)
            setVideoSize(mWidth, mHeight)
            val videoEncoder = when (mVideoCodecId) {
                VideoCodec.ID_H264 -> MediaRecorder.VideoEncoder.H264
                VideoCodec.ID_HEVC -> MediaRecorder.VideoEncoder.HEVC
                VideoCodec.ID_AV1  -> MediaRecorder.VideoEncoder.AV1
                else               -> throw IllegalArgumentException("Unknown codec id")
            }
            setVideoEncoder(videoEncoder)
            setInputSurface(surface)
            setOrientationHint(mOrientationHint)
        }

    init {
        if (useMediaRecorder) {
            mMediaRecorder = createRecorder(mInputSurface)
        } else {
            val codecProfile = when (mVideoCodecId) {
                VideoCodec.ID_HEVC -> when (dynamicRange) {
                    DynamicRangeProfiles.HLG10     -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    DynamicRangeProfiles.HDR10      -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    DynamicRangeProfiles.HDR10_PLUS -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                    else -> -1
                }
                VideoCodec.ID_AV1 -> when (dynamicRange) {
                    DynamicRangeProfiles.HLG10      -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                    DynamicRangeProfiles.HDR10      -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10
                    DynamicRangeProfiles.HDR10_PLUS -> MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
                    else -> -1
                }
                else -> -1
            }

            val format = MediaFormat.createVideoFormat(mMimeType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,
                    if (mIsHighSpeed) IFRAME_INTERVAL_HIGH_SPEED else IFRAME_INTERVAL)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (mIsHighSpeed) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                if (codecProfile != -1) {
                    setInteger(MediaFormat.KEY_PROFILE, codecProfile)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_RANGE,    MediaFormat.COLOR_RANGE_FULL)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, getTransferFunction())
                    setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing, true)
                }
            }

            if (VERBOSE) Log.d(TAG, "format: $format")
            mEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun getTransferFunction() = when (mDynamicRange) {
        DynamicRangeProfiles.HLG10      -> MediaFormat.COLOR_TRANSFER_HLG
        DynamicRangeProfiles.HDR10      -> MediaFormat.COLOR_TRANSFER_ST2084
        DynamicRangeProfiles.HDR10_PLUS -> MediaFormat.COLOR_TRANSFER_ST2084
        else -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    }

    fun getInputSurface(): Surface = mInputSurface

    fun start() {
        if (mUseMediaRecorder) {
            mMediaRecorder!!.apply { prepare(); start() }
        } else {
            mEncoder!!.start()
            mEncoderThread!!.start()
            mEncoderThread!!.waitUntilReady()
        }
    }


    fun shutdown(): Boolean {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects")
        return if (mUseMediaRecorder) {
            try {
                mMediaRecorder!!.stop()
                true
            } catch (e: RuntimeException) {
                Log.d(TAG, "stop() called too soon — deleting output file")
                mOutputFile.delete()
                false
            }
        } else {
            val handler = mEncoderThread!!.getHandler()
            handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_SHUTDOWN))
            try { mEncoderThread!!.join() }
            catch (ie: InterruptedException) {
                Log.w(TAG, "Encoder thread join interrupted", ie)
                try { mEncoderThread!!.join() } catch (_: InterruptedException) {}
            }
            true
        }
    }

    fun frameAvailable() {
        if (!mUseMediaRecorder) {
            mEncoderThread!!.getHandler()
                .sendEmptyMessage(EncoderThread.EncoderHandler.MSG_FRAME_AVAILABLE)
        }
    }

    fun waitForFirstFrame() {
        if (!mUseMediaRecorder) mEncoderThread!!.waitForFirstFrame()
    }

    private class EncoderThread(
        mediaCodec: MediaCodec,
        outputFile: File,
        orientationHint: Int,
        isHighSpeed: Boolean = false
    ) : Thread() {

        val mEncoder = mediaCodec
        var mEncodedFormat: MediaFormat? = null
        val mBufferInfo = MediaCodec.BufferInfo()
        val mMuxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val mOrientationHint = orientationHint
        val mIsHighSpeed = isHighSpeed
        var mVideoTrack = -1
        var mHandler: EncoderHandler? = null
        var mFrameNum = 0
        val mLock = Object()
        @Volatile var mReady = false
        @Volatile var mReleased = false  // true once mEncoder has been released

        override fun run() {
            if (mIsHighSpeed) Process.setThreadPriority(Process.THREAD_PRIORITY_VIDEO)
            Looper.prepare()
            mHandler = EncoderHandler(this)
            Log.d(TAG, "encoder thread ready")
            synchronized(mLock) { mReady = true; mLock.notify() }
            Looper.loop()
            synchronized(mLock) { mReady = false; mHandler = null }
            Log.d(TAG, "looper quit")
        }

        fun waitUntilReady() {
            synchronized(mLock) {
                while (!mReady) try { mLock.wait() } catch (_: InterruptedException) {}
            }
        }

        fun waitForFirstFrame() {
            synchronized(mLock) {
                while (mFrameNum < 1) try { mLock.wait() } catch (_: InterruptedException) {}
            }
            Log.d(TAG, "Waited for first frame")
        }

        fun getHandler(): EncoderHandler {
            synchronized(mLock) { if (!mReady) throw RuntimeException("not ready") }
            return mHandler!!
        }

        fun drainEncoder(): Boolean {
            val TIMEOUT_USEC = 0L
            var encodedFrame = false
            while (true) {
                val status = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC)
                when {
                    status == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        mEncodedFormat = mEncoder.outputFormat
                        Log.d(TAG, "encoder output format changed: $mEncodedFormat")
                    }
                    status < 0 -> Log.w(TAG, "unexpected dequeueOutputBuffer status: $status")
                    else -> {
                        val encodedData: ByteBuffer = mEncoder.getOutputBuffer(status)
                            ?: throw RuntimeException("encoderOutputBuffer $status was null")

                        if ((mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0)
                            mBufferInfo.size = 0

                        if (mBufferInfo.size != 0) {
                            encodedData.position(mBufferInfo.offset)
                            encodedData.limit(mBufferInfo.offset + mBufferInfo.size)
                            if (mVideoTrack == -1) {
                                mVideoTrack = mMuxer.addTrack(mEncodedFormat!!)
                                mMuxer.setOrientationHint(mOrientationHint)
                                mMuxer.start()
                                Log.d(TAG, "Started media muxer")
                            }
                            mMuxer.writeSampleData(mVideoTrack, encodedData, mBufferInfo)
                            encodedFrame = true
                        }
                        mEncoder.releaseOutputBuffer(status, false)
                        if ((mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.w(TAG, "reached end of stream unexpectedly"); break
                        }
                    }
                }
            }
            return encodedFrame
        }

        fun frameAvailable() {
            if (VERBOSE) Log.d(TAG, "frameAvailable")
            if (mReleased) return
            try {
                if (drainEncoder()) {
                    synchronized(mLock) { mFrameNum++; mLock.notify() }
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "frameAvailable: codec already released, skipping drain")
            }
        }

        fun shutdown() {
            if (VERBOSE) Log.d(TAG, "shutdown")
            try { mEncoder.signalEndOfInputStream() } catch (e: Exception) {
                Log.w(TAG, "signalEndOfInputStream failed", e)
            }
            val TIMEOUT_USEC = 10_000L
            var eos = false
            var safety = 500
            while (!eos && safety-- > 0) {
                val status = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC)
                when {
                    status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    }
                    status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        mEncodedFormat = mEncoder.outputFormat
                    }
                    status >= 0 -> {
                        if ((mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && mBufferInfo.size != 0) {
                            val encodedData = mEncoder.getOutputBuffer(status)
                            if (encodedData != null) {
                                encodedData.position(mBufferInfo.offset)
                                encodedData.limit(mBufferInfo.offset + mBufferInfo.size)
                                if (mVideoTrack == -1 && mEncodedFormat != null) {
                                    mVideoTrack = mMuxer.addTrack(mEncodedFormat!!)
                                    mMuxer.setOrientationHint(mOrientationHint)
                                    mMuxer.start()
                                    Log.d(TAG, "Muxer started in final drain")
                                }
                                if (mVideoTrack != -1) {
                                    mMuxer.writeSampleData(mVideoTrack, encodedData, mBufferInfo)
                                }
                            }
                        }
                        mEncoder.releaseOutputBuffer(status, false)
                        if ((mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eos = true
                        }
                    }
                }
            }
            if (!eos) Log.w(TAG, "EOS not received in final drain — muxer may be incomplete")
            if (mVideoTrack != -1) {
                try { mMuxer.stop() } catch (e: Exception) { Log.w(TAG, "muxer stop", e) }
                mMuxer.release()
            } else {
                Log.w(TAG, "No video track — nothing was written to muxer")
                mMuxer.release()
            }
            mReleased = true
            try { mEncoder.stop()    } catch (e: Exception) { Log.w(TAG, "encoder stop", e) }
            try { mEncoder.release() } catch (e: Exception) { Log.w(TAG, "encoder release", e) }
            Looper.myLooper()!!.quit()
        }

        class EncoderHandler(et: EncoderThread) : Handler() {
            companion object {
                const val MSG_FRAME_AVAILABLE = 0
                const val MSG_SHUTDOWN = 1
            }
            private val mWeakEncoderThread = WeakReference(et)
            override fun handleMessage(msg: Message) {
                val et = mWeakEncoderThread.get() ?: run {
                    Log.w(TAG, "EncoderHandler: weak ref is null"); return
                }
                when (msg.what) {
                    MSG_FRAME_AVAILABLE -> et.frameAvailable()
                    MSG_SHUTDOWN        -> et.shutdown()
                    else -> throw RuntimeException("unknown message ${msg.what}")
                }
            }
        }
    }
}
