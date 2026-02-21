package com.example.highspeedcamera.utils

import android.media.MediaFormat

enum class VideoCodec(val displayName: String, val mimeType: String) {
    HEVC("HEVC (H.265)", MediaFormat.MIMETYPE_VIDEO_HEVC),
    H264("H.264",        MediaFormat.MIMETYPE_VIDEO_AVC),
    AV1 ("AV1",          MediaFormat.MIMETYPE_VIDEO_AV1);

    companion object {
        // Numeric IDs kept for EncoderWrapper
        const val ID_HEVC = 0
        const val ID_H264 = 1
        const val ID_AV1  = 2

        fun fromId(id: Int): VideoCodec = when (id) {
            ID_HEVC -> HEVC
            ID_H264 -> H264
            ID_AV1  -> AV1
            else    -> throw IllegalArgumentException("Unknown codec id $id")
        }

        fun idToMime(id: Int): String = fromId(id).mimeType
    }
}
