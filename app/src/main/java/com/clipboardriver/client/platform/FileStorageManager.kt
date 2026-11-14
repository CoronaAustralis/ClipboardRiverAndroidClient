package com.clipboardriver.client.platform

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.clipboardriver.client.R
import com.clipboardriver.client.StringProvider
import com.clipboardriver.client.net.DownloadedBlob
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ImportedFileAsset(val file: File, val fileName: String, val mimeType: String)
data class SavedRemoteFile(val displayName: String, val uriString: String, val mimeType: String)
class FileLimitExceeded : IOException()

interface FileStorage {
    suspend fun importSharedFile(uri: Uri, maxBytes: Long): ImportedFileAsset
    suspend fun saveRemoteFile(blob: DownloadedBlob, itemId: Long, mimeHint: String, nameHint: String?): SavedRemoteFile
}

class FileStorageManager(private val context: Context, private val strings: StringProvider) : FileStorage {
    private val resolver get() = context.contentResolver
    private val directory = File(context.cacheDir, "transfers")
    private val preparation = Mutex()
    private var prepared = false

    suspend fun prepare() = withContext(Dispatchers.IO) {
        preparation.withLock {
            if (!prepared) {
                check(directory.isDirectory || directory.mkdirs()) { strings.get(R.string.cr_file_write_failed) }
                // Only this new transfer cache is owned here. Old history files remain untouched.
                directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
                prepared = true
            }
        }
    }

    override suspend fun importSharedFile(uri: Uri, maxBytes: Long): ImportedFileAsset {
        var temporary: File? = null
        try {
            return withContext(Dispatchers.IO) {
                prepare()
                var name: String? = null
                var reportedSize: Long? = null
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let { name = cursor.getString(it) }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let { reportedSize = cursor.getLong(it) }
                    }
                }
                if (fileExceedsUploadLimit(maxBytes, reportedSize ?: 0)) throw FileLimitExceeded()
                val safeName = sanitizeFileName(name).ifBlank { "shared-file" }
                val mimeType = resolver.getType(uri)?.substringBefore(';')?.takeIf { it.isNotBlank() }
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(safeName.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val target = File.createTempFile("upload-", ".tmp", directory).also { temporary = it }
                val input = resolver.openInputStream(uri) ?: throw IOException(strings.get(R.string.cr_file_read_failed))
                input.use { source ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var size = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            size += count
                            if (fileExceedsUploadLimit(maxBytes, size)) throw FileLimitExceeded()
                            output.write(buffer, 0, count)
                        }
                        if (size == 0L) throw IOException(strings.get(R.string.cr_file_empty))
                    }
                }
                ImportedFileAsset(target, safeName, mimeType)
            }
        } catch (failure: Throwable) {
            // Also handles cancellation while dispatching the completed IO result back to the caller.
            temporary?.delete()
            throw failure
        }
    }

    override suspend fun saveRemoteFile(
        blob: DownloadedBlob,
        itemId: Long,
        mimeHint: String,
        nameHint: String?,
    ): SavedRemoteFile = withContext(Dispatchers.IO) {
        val mime = (blob.contentType?.takeIf { it.isNotBlank() } ?: mimeHint)
            .substringBefore(';').trim().ifBlank { "application/octet-stream" }
        val isImage = mime.startsWith("image/", ignoreCase = true)
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val name = sanitizeFileName(blob.fileName ?: nameHint)
            .ifBlank { "remote-" + itemId + (extension?.let { "." + it } ?: "") }
        val collection = if (isImage) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                (if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/ClipboardRiver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException(strings.get(R.string.cr_file_write_failed))
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException(strings.get(R.string.cr_file_write_failed))
            output.use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = blob.stream.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                }
                destination.flush()
            }
            currentCoroutineContext().ensureActive()
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) { strings.get(R.string.cr_file_write_failed) }
            SavedRemoteFile(name, uri.toString(), mime)
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }
}

internal fun fileExceedsUploadLimit(limit: Long, size: Long): Boolean =
    limit == -1L || (limit > 0 && size > limit)

internal fun sanitizeFileName(value: String?): String =
    value.orEmpty().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(180)