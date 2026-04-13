package com.clipboardriver.client.platform

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.clipboardriver.client.AppStrings
import com.clipboardriver.client.R
import com.clipboardriver.client.net.DownloadedBlob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class ImportedImageAsset(
    val filePath: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val contentHash: String,
)

data class SavedRemoteImage(
    val displayName: String,
    val uriString: String,
)

class ImageStorageManager(
    private val context: Context,
    private val strings: AppStrings,
) {
    private val resolver
        get() = context.contentResolver

    suspend fun importSharedImage(sourceUri: Uri): ImportedImageAsset = withContext(Dispatchers.IO) {
        val originalName = queryDisplayName(sourceUri)
        val mimeType = resolveImageMimeType(sourceUri, originalName)
        val extension = extensionFor(mimeType, originalName)
        val baseName = sanitizeBaseName(originalName?.substringBeforeLast('.').orEmpty())
            .ifBlank { "shared-${System.currentTimeMillis()}" }
        val targetFile = File(historyImageDirectory(), "${UUID.randomUUID()}-$baseName$extension")

        val digest = MessageDigest.getInstance("SHA-256")
        var fileSize = 0L
        resolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    fileSize += read
                }
            }
        } ?: error(strings.get(R.string.image_storage_shared_read_failed))

        if (fileSize <= 0L) {
            targetFile.delete()
            error(strings.get(R.string.image_storage_shared_empty))
        }

        ImportedImageAsset(
            filePath = targetFile.absolutePath,
            fileName = (sanitizeFileName(originalName).ifBlank { targetFile.name }),
            mimeType = mimeType,
            fileSize = fileSize,
            contentHash = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    suspend fun saveRemoteImageToPictures(
        blob: DownloadedBlob,
        serverItemId: Long,
        mimeTypeHint: String?,
        blobNameHint: String?,
    ): SavedRemoteImage = withContext(Dispatchers.IO) {
        val mimeType = listOf(blob.contentType, mimeTypeHint)
            .firstOrNull { !it.isNullOrBlank() && it.startsWith("image/") }
            ?: "image/png"
        val extension = extensionFor(mimeType, blob.fileName ?: blobNameHint)
        val fileName = sanitizeFileName(blob.fileName ?: blobNameHint)
            .ifBlank { "remote-$serverItemId$extension" }
            .ensureImageExtension(extension)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ClipboardRiver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error(strings.get(R.string.image_storage_pictures_create_failed))

        try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(blob.bytes)
                output.flush()
            } ?: error(strings.get(R.string.image_storage_pictures_write_failed))

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            SavedRemoteImage(
                displayName = fileName,
                uriString = uri.toString(),
            )
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            throw throwable
        }
    }

    fun deleteLocalImage(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            File(path).takeIf { it.exists() }?.delete()
        }
    }

    private fun historyImageDirectory(): File =
        (context.getExternalFilesDir("history_images")
            ?: context.getDir("image_cache", Context.MODE_PRIVATE)).apply {
            mkdirs()
        }

    private fun queryDisplayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
        }

    private fun resolveImageMimeType(uri: Uri, originalName: String?): String {
        val contentMime = resolver.getType(uri)?.takeIf { it.startsWith("image/") }
        if (contentMime != null) return contentMime

        val extension = originalName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        val guessed = extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        return guessed?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
    }

    private fun extensionFor(mimeType: String, fileNameHint: String?): String {
        val fromName = fileNameHint
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?.let { ".$it" }
        if (!fromName.isNullOrBlank()) return fromName

        val fromMime = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType.substringBefore(';').lowercase())
            ?.takeIf { it.isNotBlank() }
        return if (fromMime != null) ".$fromMime" else ".jpg"
    }

    private fun sanitizeBaseName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()

    private fun sanitizeFileName(value: String?): String =
        sanitizeBaseName(value.orEmpty())
            .replace(Regex("\\s+"), " ")

    private fun String.ensureImageExtension(extension: String): String =
        if (contains('.')) this else this + extension
}
