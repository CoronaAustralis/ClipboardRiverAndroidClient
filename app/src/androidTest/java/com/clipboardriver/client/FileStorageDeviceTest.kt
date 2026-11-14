package com.clipboardriver.client

import androidx.test.platform.app.InstrumentationRegistry
import com.clipboardriver.client.net.DownloadedBlob
import com.clipboardriver.client.platform.FileStorageManager
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import android.provider.MediaStore

class FileStorageDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun downloadedFileIsPublishedInDownloadsAndCanBeOpened() = runBlocking {
        val storage = FileStorageManager(context, AppStrings(context))
        val bytes = "Clipboard River fixture".toByteArray()
        var closed = false
        val stream = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        val name = "clipboard-river-qa-" + UUID.randomUUID() + ".txt"
        val saved = DownloadedBlob(stream, "text/plain", name).use {
            storage.saveRemoteFile(it, 12, "text/plain", name)
        }
        val uri = android.net.Uri.parse(saved.uriString)
        try {
            assertTrue(closed)
            context.contentResolver.query(uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Download/ClipboardRiver/", it.getString(0))
                assertEquals(0, it.getInt(1))
            }
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun failedDownloadRemovesPendingMediaStoreEntry() = runBlocking {
        val storage = FileStorageManager(context, AppStrings(context))
        val name = "clipboard-river-failed-qa-" + UUID.randomUUID() + ".pdf"
        val stream = object : InputStream() {
            var reads = 0
            override fun read(): Int {
                if (++reads > 8) throw IOException("fixture interruption")
                return 65
            }
        }
        try {
            DownloadedBlob(stream, "application/pdf", name).use {
                storage.saveRemoteFile(it, 13, "application/pdf", name)
            }
            fail("Expected interrupted download")
        } catch (_: IOException) {
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID), MediaStore.MediaColumns.DISPLAY_NAME + " = ?",
                arrayOf(name), null)!!.use { assertEquals(0, it.count) }
        }
    }
}