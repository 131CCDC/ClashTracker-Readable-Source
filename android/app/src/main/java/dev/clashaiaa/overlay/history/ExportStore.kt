package dev.clashaiaa.overlay.history

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Where an export landed, so the UI can show a path the user can actually open. */
data class ExportResult(
    val fileName: String,
    /** Always present: the app's own external directory, reachable over MTP. */
    val appPath: String,
    /** Present when the copy into the public Downloads collection succeeded. */
    val publicPath: String? = null,
    val bytes: Int = 0,
    val rows: Int = 0,
) {
    val summary: String
        get() = buildString {
            append("$fileName · $rows 行 · $bytes 字节")
            if (publicPath != null) append("\n下载目录: $publicPath")
            append("\n应用目录: $appPath")
        }
}

/**
 * Writes an export to two places.
 *
 * The app-specific directory always works and needs no permission; the public
 * `Download/ClashTracker` copy goes through MediaStore on API 29+ so the file
 * is reachable from any file manager or over the USB connection without the
 * user granting storage access to anything.
 */
object ExportStore {

    private const val PUBLIC_FOLDER = "ClashTracker"

    fun write(context: Context, fileName: String, content: String): ExportResult {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports")
        if (!directory.exists()) directory.mkdirs()
        val file = File(directory, fileName)
        file.writeBytes(bytes)

        var publicPath: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publicPath = runCatching { writeToDownloads(context, fileName, bytes) }.getOrNull()
        }
        return ExportResult(
            fileName = fileName,
            appPath = file.absolutePath,
            publicPath = publicPath,
            bytes = bytes.size,
        )
    }

    private fun writeToDownloads(context: Context, fileName: String, bytes: ByteArray): String? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Replace an earlier export of the same name instead of stacking copies.
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(fileName, "%$PUBLIC_FOLDER%"),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(fileName))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + PUBLIC_FOLDER,
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { stream -> stream.write(bytes) }
                ?: return null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (exc: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_FOLDER/$fileName"
    }

    private fun mimeOf(fileName: String): String =
        if (fileName.endsWith(".json", ignoreCase = true)) "application/json" else "text/csv"
}
