package com.example.highspeedcamera.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "GalleryUtils"

fun createTempVideoFile(context: Context): File {
    val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
    return File(context.filesDir, "VID_${sdf.format(Date())}.mp4")
}

fun saveToGallery(
    context: Context,
    file: File,
    fps: Int = 120,
    iso: Int = 400,
    shutterNs: Long = 1_000_000_000L / 120
): Uri? {
    val timestamp    = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val shutterDenom = (1_000_000_000.0 / shutterNs).roundToInt()
    val displayName  = "HighSpeed_${fps}fps_ISO${iso}_SS1-${shutterDenom}s_$timestamp.mp4"
    val description  = "${fps} fps | ISO $iso | Shutter 1/${shutterDenom}s | $timestamp"

    Mp4MetadataWriter.inject(
        file   = file,
        fields = mapOf(
            "©cmt" to description,
            "©nam" to displayName,
            "©day" to timestamp,
            "©too" to "HighSpeedCamera | ${fps}fps"
        )
    )

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.DESCRIPTION, description)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            Log.i(TAG, "Saved: $displayName  ($description)")
            uri
        } else {
            @Suppress("DEPRECATION")
            val moviesDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
            )
            moviesDir.mkdirs()
            val destFile = File(moviesDir, displayName)
            file.copyTo(destFile, overwrite = true)
            file.delete()
            MediaScannerConnection.scanFile(
                context, arrayOf(destFile.absolutePath), null, null
            )
            Log.i(TAG, "Saved (legacy): ${destFile.absolutePath}")
            Uri.fromFile(destFile)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save video to gallery", e)
        null
    }
}