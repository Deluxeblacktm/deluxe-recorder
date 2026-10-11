package com.deluxe.recorder.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

data class RecordingEntry(
    val id: Long,
    val name: String,
    val size: Long,
    val uri: Uri
)

object FileUtils {

    private const val FOLDER_NAME = "Deluxe Recorder"
    private const val PHOTOS_FOLDER = "Deluxe Recorder Fotos"
    private val fileNameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    // Cache de miniaturas de 24 MB (~200 miniaturas) em KB para manter 120 FPS fluido sem recargas
    private val thumbnailCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount + 1023) / 1024
    }

    fun getCachedThumbnail(key: String): Bitmap? = thumbnailCache.get(key)

    fun getImageThumbnail(context: Context, uri: Uri, width: Int = 120, height: Int = 90): Bitmap? {
        val cacheKey = "img@$uri@${width}x$height"
        thumbnailCache.get(cacheKey)?.let { return it }

        val bitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(width, height), null)
            } else {
                BitmapFactory.decodeStream(
                    context.contentResolver.openInputStream(uri)
                )
            }
        } catch (_: Exception) {
            null
        }

        bitmap?.let { thumbnailCache.put(cacheKey, it) }
        return bitmap
    }

    fun getVideoThumbnail(context: Context, uri: Uri, targetPx: Int): Bitmap? {
        val cacheKey = "$uri@$targetPx"
        thumbnailCache.get(cacheKey)?.let { return it }

        val bitmap = try {
            MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    mmr.getScaledFrameAtTime(
                        1_000_000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetPx,
                        targetPx
                    )
                } else {
                    @Suppress("DEPRECATION")
                    mmr.frameAtTime?.let { original ->
                        val scale = minOf(1f, targetPx.toFloat() / original.width)
                        if (scale < 1f) {
                            Bitmap.createScaledBitmap(
                                original,
                                (original.width * scale).toInt(),
                                (original.height * scale).toInt(),
                                true
                            ).also {
                                if (it != original) original.recycle()
                            }
                        } else {
                            original
                        }
                    }
                }
            }
        } catch (e: Exception) {
            null
        }

        bitmap?.let { thumbnailCache.put(cacheKey, it) }
        return bitmap
    }

    fun createOutputFile(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val name = "Deluxe_Recorder_${LocalDateTime.now().format(fileNameFormatter)}.mp4"
        return File(dir, name)
    }

    fun copyToPublicStorage(context: Context, sourceFile: File): File? {
        val fileName = sourceFile.name
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            copyViaMediaStore(context, sourceFile, fileName)
        } else {
            copyDirectly(sourceFile, fileName)
        }
    }

    private fun copyViaMediaStore(context: Context, sourceFile: File, fileName: String): File? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

        return try {
            sourceFile.inputStream().use { input ->
                resolver.openOutputStream(uri).use { output ->
                    if (output == null) {
                        resolver.delete(uri, null, null)
                        return null
                    }
                    input.copyTo(output)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            File(getPublicMoviesDir(), fileName)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun copyDirectly(sourceFile: File, fileName: String): File? {
        return try {
            val destDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                FOLDER_NAME
            )
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
            destFile
        } catch (e: Exception) {
            null
        }
    }

    fun getPublicMoviesDir(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            FOLDER_NAME
        )
    }

    fun shareVideo(context: Context, entry: RecordingEntry) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, entry.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar vídeo"))
    }

    fun shareEntry(context: Context, entry: RecordingEntry, isVideo: Boolean = true) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/mp4" else "image/png"
            putExtra(Intent.EXTRA_STREAM, entry.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, if (isVideo) "Compartilhar vídeo" else "Compartilhar foto"))
    }

    fun deleteEntry(context: Context, entry: RecordingEntry): Boolean {
        return try {
            context.contentResolver.delete(entry.uri, null, null) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun getFileSizeString(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            else -> String.format("%.1f MB", size / (1024.0 * 1024.0))
        }
    }

    fun getRecordings(context: Context): List<RecordingEntry> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getRecordingsFromMediaStore(context)
        } else {
            getRecordingsFromDirectory(context)
        }
    }

    fun getTotalRecordingDurationMs(context: Context): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(MediaStore.Video.Media.DURATION)
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.SIZE} > 0"
            val selectionArgs = arrayOf("${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME%")

            var total = 0L
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (cursor.moveToNext()) {
                    total += cursor.getLong(durationColumn)
                }
            }
            return total
        }

        return getRecordingsFromDirectory(context).sumOf { entry ->
            try {
                MediaMetadataRetriever().use { mmr ->
                    mmr.setDataSource(context, entry.uri)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
            } catch (e: Exception) {
                0L
            }
        }
    }

    private fun getRecordingsFromMediaStore(context: Context): List<RecordingEntry> {
        val entries = mutableListOf<RecordingEntry>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.SIZE} > 0"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME%")

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: continue
                val size = cursor.getLong(sizeColumn)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                )
                entries.add(RecordingEntry(id, name, size, uri))
            }
        }
        return entries
    }

    private fun getRecordingsFromDirectory(context: Context): List<RecordingEntry> {
        val dir = getPublicMoviesDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapIndexed { index, file ->
                RecordingEntry(
                    id = index.toLong(),
                    name = file.name,
                    size = file.length(),
                    uri = Uri.fromFile(file)
                )
            }
            ?: emptyList()
    }

    fun getScreenshots(context: Context): List<RecordingEntry> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getScreenshotsFromMediaStore(context)
        } else {
            getScreenshotsFromDirectory(context)
        }
    }

    private fun getScreenshotsFromMediaStore(context: Context): List<RecordingEntry> {
        val entries = mutableListOf<RecordingEntry>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.SIZE} > 0"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_PICTURES}/$PHOTOS_FOLDER%")

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: continue
                val size = cursor.getLong(sizeColumn)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                entries.add(RecordingEntry(id, name, size, uri))
            }
        }
        return entries
    }

    private fun getScreenshotsFromDirectory(context: Context): List<RecordingEntry> {
        val dir = getPublicPicturesDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "png" || it.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapIndexed { index, file ->
                RecordingEntry(
                    id = index.toLong(),
                    name = file.name,
                    size = file.length(),
                    uri = Uri.fromFile(file)
                )
            }
            ?: emptyList()
    }

    fun saveScreenshot(context: Context, bitmap: Bitmap): File? {
        val name = "Deluxe_Recorder_fotos_${LocalDateTime.now().format(fileNameFormatter)}.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScreenshotViaMediaStore(context, bitmap, name)
        } else {
            saveScreenshotDirectly(context, bitmap, name)
        }
    }

    private fun saveScreenshotViaMediaStore(context: Context, bitmap: Bitmap, name: String): File? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$PHOTOS_FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            File(getPublicPicturesDir(), name)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun saveScreenshotDirectly(context: Context, bitmap: Bitmap, name: String): File? {
        return try {
            val dir = getPublicPicturesDir()
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun getPublicPicturesDir(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            PHOTOS_FOLDER
        )
    }
}
