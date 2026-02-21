package com.example.highspeedcamera.utils

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "Mp4MetadataWriter"

/**
 * Injects iTunes-style metadata into an MP4 file by rewriting the moov/udta box.
 *
 * Uses the MPEG-4 / iTunes metadata format (moov › udta › meta › ilst) which is
 * what ffprobe, VLC, MediaInfo and the stock Android camera all use:
 *
 *   moov
 *     udta
 *       meta  [FullBox: version(1)+flags(3)]
 *         hdlr  [handler="mdir"]
 *         ilst
 *           ©cmt → ffprobe format.tags.comment
 *           ©nam → ffprobe format.tags.title
 *           ©day → ffprobe format.tags.date
 *           ©too → ffprobe format.tags.encoder
 *
 * Android's MediaMuxer writes moov AFTER mdat, so enlarging / replacing moov
 * does NOT shift any chunk offsets in stco/co64.
 */
object Mp4MetadataWriter {

    fun inject(file: File, fields: Map<String, String>): Boolean {
        if (fields.isEmpty()) return true
        return try {
            injectInternal(file, fields)
            Log.i(TAG, "Injected ${fields.size} tags into ${file.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "inject failed for ${file.name} — ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }



    private fun injectInternal(file: File, fields: Map<String, String>) {
        RandomAccessFile(file, "rw").use { raf ->
            val fileLen = raf.length()
            Log.d(TAG, "File length: $fileLen")

            val (moovOff, moovLen) = findTopLevelBox(raf, fileLen, "moov")
                ?: error("moov box not found — is this a valid MP4?")
            Log.d(TAG, "moov at offset=$moovOff  size=$moovLen")


            raf.seek(moovOff + 8)
            val moovPayload = ByteArray((moovLen - 8).toInt()).also { raf.readFully(it) }

            val payloadNoUdta = removeChildBox(moovPayload, "udta")
            val newUdta       = buildUdta(fields)

            val newMoovSize = 8 + payloadNoUdta.size + newUdta.size
            val newMoov = ByteBuffer.allocate(newMoovSize).order(ByteOrder.BIG_ENDIAN).run {
                putInt(newMoovSize)
                put("moov".toByteArray(Charsets.US_ASCII))
                put(payloadNoUdta)
                put(newUdta)
                array()
            }

            raf.setLength(moovOff)
            raf.seek(moovOff)
            raf.write(newMoov)
            Log.d(TAG, "Wrote ${newMoov.size} bytes at offset $moovOff")
        }
    }

    private fun findTopLevelBox(raf: RandomAccessFile, fileLen: Long, name: String): Pair<Long, Long>? {
        var pos = 0L
        while (pos + 8 <= fileLen) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val type   = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)

            val actualSize: Long = when (size32) {
                0L   -> fileLen - pos
                1L   -> raf.readLong()
                else -> size32
            }

            if (actualSize < 8) {
                Log.w(TAG, "Malformed box size $actualSize at offset $pos — stopping")
                break
            }
            Log.v(TAG, "  top-level box '$type' size=$actualSize at $pos")
            if (type == name) return pos to actualSize
            pos += actualSize
        }
        return null
    }

    private fun removeChildBox(payload: ByteArray, name: String): ByteArray {
        val src = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val dst = ByteBuffer.allocate(payload.size).order(ByteOrder.BIG_ENDIAN)
        while (src.remaining() >= 8) {
            val mark  = src.position()
            val size  = src.int.toLong() and 0xFFFFFFFFL
            val type  = ByteArray(4).also { src.get(it) }.toString(Charsets.US_ASCII)
            val iSize = size.toInt()
            if (iSize < 8 || iSize > src.capacity()) break
            src.position(mark)
            if (type == name) {
                src.position(mark + iSize)
            } else {
                val box = ByteArray(iSize)
                src.get(box)
                dst.put(box)
            }
        }
        return dst.array().copyOf(dst.position())
    }

    private fun buildUdta(fields: Map<String, String>): ByteArray {
        val ilst = buildIlst(fields)
        val hdlr = buildHdlr()

        val metaBodySize = 4 + hdlr.size + ilst.size
        val metaSize     = 8 + metaBodySize
        val meta = ByteBuffer.allocate(metaSize).order(ByteOrder.BIG_ENDIAN).run {
            putInt(metaSize)
            put("meta".toByteArray(Charsets.US_ASCII))
            putInt(0)           // version=0, flags=0
            put(hdlr)
            put(ilst)
            array()
        }

        val udtaSize = 8 + meta.size
        return ByteBuffer.allocate(udtaSize).order(ByteOrder.BIG_ENDIAN).run {
            putInt(udtaSize)
            put("udta".toByteArray(Charsets.US_ASCII))
            put(meta)
            array()
        }
    }


    private fun buildHdlr(): ByteArray {
        val size = 4 + 4 + 4 + 4 + 4 + 12 + 1   // = 33
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).run {
            putInt(size)
            put("hdlr".toByteArray(Charsets.US_ASCII))
            putInt(0)
            putInt(0)
            put("mdir".toByteArray(Charsets.US_ASCII))
            putInt(0); putInt(0); putInt(0)
            put(0)
            array()
        }
    }

    private fun buildIlst(fields: Map<String, String>): ByteArray {
        val items = fields.map { (key, value) ->
            val strBytes = value.toByteArray(Charsets.UTF_8)

            val keyBytes = key.toByteArray(Charsets.ISO_8859_1).let {
                if (it.size == 4) it else it.copyOf(4)
            }
            val dataSize = 4 + 4 + 4 + 4 + strBytes.size
            val itemSize = 4 + 4 + dataSize
            ByteBuffer.allocate(itemSize).order(ByteOrder.BIG_ENDIAN).run {
                putInt(itemSize)
                put(keyBytes)
                putInt(dataSize)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(1)
                putInt(0)
                put(strBytes)
                array()
            }
        }
        val ilstSize = 8 + items.sumOf { it.size }
        return ByteBuffer.allocate(ilstSize).order(ByteOrder.BIG_ENDIAN).run {
            putInt(ilstSize)
            put("ilst".toByteArray(Charsets.US_ASCII))
            items.forEach { put(it) }
            array()
        }
    }
}
