package de.luftzeichnen.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val FOLDER = "LuftZeichnen"

private fun timestampName(ext: String): String =
    "LuftZeichnen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + ext

/** Speichert ein Foto in der Galerie (Bilder/LuftZeichnen). Gibt den Speicherort zurück. */
fun savePhoto(context: Context, bitmap: Bitmap): String {
    val name = timestampName("jpg")
    if (Build.VERSION.SDK_INT >= 29) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ?: throw IOException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return Environment.DIRECTORY_PICTURES + "/" + FOLDER
    } else {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return file.absolutePath
    }
}

/** Nimmt genau das auf, was [EffectView.render] zeichnet (ohne Knöpfe), als MP4. */
class VideoRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var surface: Surface? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var file: File? = null
    private var framesWritten = 0

    var width = 0
        private set
    var height = 0
        private set
    var startedAt = 0L
        private set

    val isRecording: Boolean get() = recorder != null

    fun start(viewWidth: Int, viewHeight: Int) {
        if (isRecording) return
        width = 720
        height = (((720f * viewHeight / viewWidth.coerceAtLeast(1)) / 16f).roundToInt() * 16).coerceIn(720, 1920)
        val name = timestampName("mp4")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val u = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert failed")
                uri = u
                val fd = context.contentResolver.openFileDescriptor(u, "w")
                    ?: throw IOException("openFileDescriptor failed")
                pfd = fd
                r.setOutputFile(fd.fileDescriptor)
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
                val f = File(dir, name)
                file = f
                r.setOutputFile(f.absolutePath)
            }
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(width, height)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(10_000_000)
            r.prepare()
            surface = r.surface
            r.start()
            recorder = r
            framesWritten = 0
            startedAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            r.release()
            finish(false)
            throw e
        }
    }

    fun drawFrame(draw: (Canvas, Int, Int) -> Unit) {
        val s = surface ?: return
        if (recorder == null) return
        val c = try {
            s.lockHardwareCanvas()
        } catch (e: Exception) {
            Log.w(TAG, "lockHardwareCanvas failed", e)
            return
        }
        try {
            draw(c, width, height)
        } finally {
            s.unlockCanvasAndPost(c)
        }
        framesWritten++
    }

    /** Beendet die Aufnahme. Gibt den Speicherort zurück oder null, wenn nichts gespeichert wurde. */
    fun stop(): String? {
        val r = recorder ?: return null
        recorder = null
        var ok = framesWritten > 0
        try {
            r.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
            ok = false
        }
        r.release()
        return finish(ok)
    }

    private fun finish(ok: Boolean): String? {
        surface?.release()
        surface = null
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
        var result: String? = null
        uri?.let { u ->
            val resolver = context.contentResolver
            if (ok) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(u, values, null, null)
                result = Environment.DIRECTORY_MOVIES + "/" + FOLDER
            } else {
                resolver.delete(u, null, null)
            }
        }
        file?.let { f ->
            if (ok) result = f.absolutePath else f.delete()
        }
        uri = null
        file = null
        return result
    }

    private companion object {
        const val TAG = "VideoRecorder"
    }
}
