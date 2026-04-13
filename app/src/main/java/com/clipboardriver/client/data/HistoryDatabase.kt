package com.clipboardriver.client.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.clipboardriver.client.model.ContentKind
import com.clipboardriver.client.model.HistoryItem
import com.clipboardriver.client.model.LocalHistoryMode
import com.clipboardriver.client.model.RuntimeState
import com.clipboardriver.client.model.SyncState
import com.clipboardriver.client.sync.HistoryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File

private class ClipboardDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history_items (
                local_id INTEGER PRIMARY KEY AUTOINCREMENT,
                client_item_id TEXT NOT NULL UNIQUE,
                content_kind TEXT NOT NULL DEFAULT 'TEXT',
                text_content TEXT NOT NULL,
                image_path TEXT,
                mime_type TEXT,
                skip_reason TEXT,
                content_hash TEXT NOT NULL,
                client_created_at TEXT NOT NULL,
                sync_state TEXT NOT NULL,
                server_cursor INTEGER,
                server_item_id INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE runtime_state (
                singleton_id INTEGER PRIMARY KEY,
                last_remote_cursor INTEGER NOT NULL,
                websocket_state TEXT NOT NULL,
                last_error TEXT,
                last_successful_heartbeat_at TEXT,
                last_successful_upload_at TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO runtime_state (
                singleton_id,
                last_remote_cursor,
                websocket_state,
                last_error,
                last_successful_heartbeat_at,
                last_successful_upload_at
            ) VALUES (0, 0, '', NULL, NULL, NULL)
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE history_items ADD COLUMN content_kind TEXT NOT NULL DEFAULT 'TEXT'")
            db.execSQL("ALTER TABLE history_items ADD COLUMN image_path TEXT")
            db.execSQL("ALTER TABLE history_items ADD COLUMN mime_type TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE history_items ADD COLUMN skip_reason TEXT")
        }
    }

    private companion object {
        const val DATABASE_NAME = "clipboard_river.db"
        const val DATABASE_VERSION = 3
    }
}

class HistoryRepository(
    context: Context,
) : HistoryStore {
    private val helper = ClipboardDatabaseHelper(context)
    private val historyState = MutableStateFlow(loadHistory())
    private val runtimeState = MutableStateFlow(loadRuntime())

    override fun visibleHistory(limit: Int): Flow<List<HistoryItem>> =
        historyState.map { it.take(limit) }

    override fun pendingCount(): Flow<Int> =
        historyState.map { items -> items.count { it.isPendingUpload } }

    override fun runtimeState(): Flow<RuntimeState> = runtimeState

    override suspend fun currentRuntime(): RuntimeState = runtimeState.value

    override suspend fun upsertRecord(item: HistoryItem) {
        helper.writableDatabase.insertWithOnConflict(
            "history_items",
            null,
            ContentValues().apply {
                put("client_item_id", item.clientItemId)
                put("content_kind", item.contentKind.name)
                put("text_content", item.textContent.orEmpty())
                item.imagePath?.let { put("image_path", it) } ?: putNull("image_path")
                item.mimeType?.let { put("mime_type", it) } ?: putNull("mime_type")
                item.skipReason?.let { put("skip_reason", it) } ?: putNull("skip_reason")
                put("content_hash", item.contentHash)
                put("client_created_at", item.clientCreatedAt)
                put("sync_state", item.syncState.name)
                item.serverCursor?.let { put("server_cursor", it) } ?: putNull("server_cursor")
                item.serverItemId?.let { put("server_item_id", it) } ?: putNull("server_item_id")
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        refreshHistory()
    }

    override suspend fun updateSyncResult(
        clientItemId: String,
        syncState: SyncState,
        serverCursor: Long?,
        serverItemId: Long?,
        skipReason: String?,
    ) {
        helper.writableDatabase.update(
            "history_items",
            ContentValues().apply {
                put("sync_state", syncState.name)
                skipReason?.let { put("skip_reason", it) } ?: putNull("skip_reason")
                serverCursor?.let { put("server_cursor", it) } ?: putNull("server_cursor")
                serverItemId?.let { put("server_item_id", it) } ?: putNull("server_item_id")
            },
            "client_item_id = ?",
            arrayOf(clientItemId),
        )
        refreshHistory()
    }

    override suspend fun pendingRecords(limit: Int): List<HistoryItem> {
        val database = helper.readableDatabase
        return database.query(
            "history_items",
            arrayOf(
                "client_item_id",
                "content_kind",
                "text_content",
                "image_path",
                "mime_type",
                "skip_reason",
                "content_hash",
                "client_created_at",
                "sync_state",
                "server_cursor",
                "server_item_id",
            ),
            "sync_state IN (?, ?)",
            arrayOf(SyncState.PENDING.name, SyncState.FAILED.name),
            null,
            null,
            "local_id ASC",
            limit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toHistoryItem())
                }
            }
        }
    }

    override suspend fun prune(mode: LocalHistoryMode, retentionDays: Int) {
        if (mode != LocalHistoryMode.DAYS) return
        val cutoff = java.time.Instant.now().minusSeconds(retentionDays.toLong() * 86_400L).toString()
        val imagePaths = collectImagePaths(
            selection = "client_created_at < ?",
            selectionArgs = arrayOf(cutoff),
        )
        helper.writableDatabase.delete(
            "history_items",
            "client_created_at < ?",
            arrayOf(cutoff),
        )
        deleteImageFiles(imagePaths)
        refreshHistory()
    }

    override suspend fun clearHistory() {
        val imagePaths = collectImagePaths(selection = null, selectionArgs = null)
        helper.writableDatabase.delete("history_items", null, null)
        deleteImageFiles(imagePaths)
        refreshHistory()
    }

    suspend fun deleteRecord(clientItemId: String) {
        val imagePaths = collectImagePaths(
            selection = "client_item_id = ?",
            selectionArgs = arrayOf(clientItemId),
        )
        helper.writableDatabase.delete(
            "history_items",
            "client_item_id = ?",
            arrayOf(clientItemId),
        )
        deleteImageFiles(imagePaths)
        refreshHistory()
    }

    override suspend fun updateRuntime(transform: (RuntimeState) -> RuntimeState) {
        val updated = transform(runtimeState.value)
        helper.writableDatabase.update(
            "runtime_state",
            ContentValues().apply {
                put("last_remote_cursor", updated.lastRemoteCursor)
                put("websocket_state", updated.websocketState)
                updated.lastError?.let { put("last_error", it) } ?: putNull("last_error")
                updated.lastSuccessfulHeartbeatAt?.let { put("last_successful_heartbeat_at", it) } ?: putNull("last_successful_heartbeat_at")
                updated.lastSuccessfulUploadAt?.let { put("last_successful_upload_at", it) } ?: putNull("last_successful_upload_at")
            },
            "singleton_id = 0",
            null,
        )
        refreshRuntime()
    }

    override suspend fun resetRuntime() {
        helper.writableDatabase.update(
            "runtime_state",
            ContentValues().apply {
                put("last_remote_cursor", 0L)
                put("websocket_state", "")
                putNull("last_error")
                putNull("last_successful_heartbeat_at")
                putNull("last_successful_upload_at")
            },
            "singleton_id = 0",
            null,
        )
        refreshRuntime()
    }

    private fun refreshHistory() {
        historyState.value = loadHistory()
    }

    private fun refreshRuntime() {
        runtimeState.value = loadRuntime()
    }

    private fun loadHistory(): List<HistoryItem> {
        val database = helper.readableDatabase
        return database.query(
            "history_items",
            arrayOf(
                "client_item_id",
                "content_kind",
                "text_content",
                "image_path",
                "mime_type",
                "skip_reason",
                "content_hash",
                "client_created_at",
                "sync_state",
                "server_cursor",
                "server_item_id",
            ),
            null,
            null,
            null,
            null,
            "local_id DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toHistoryItem())
                }
            }
        }
    }

    private fun loadRuntime(): RuntimeState {
        val database = helper.readableDatabase
        return database.query(
            "runtime_state",
            arrayOf(
                "last_remote_cursor",
                "websocket_state",
                "last_error",
                "last_successful_heartbeat_at",
                "last_successful_upload_at",
            ),
            "singleton_id = 0",
            null,
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return RuntimeState()
            RuntimeState(
                lastRemoteCursor = cursor.getLong(cursor.getColumnIndexOrThrow("last_remote_cursor")),
                websocketState = cursor.getString(cursor.getColumnIndexOrThrow("websocket_state")),
                lastError = cursor.getStringOrNull("last_error"),
                lastSuccessfulHeartbeatAt = cursor.getStringOrNull("last_successful_heartbeat_at"),
                lastSuccessfulUploadAt = cursor.getStringOrNull("last_successful_upload_at"),
            )
        }
    }

    private fun collectImagePaths(
        selection: String?,
        selectionArgs: Array<String>?,
    ): List<String> {
        val database = helper.readableDatabase
        return database.query(
            "history_items",
            arrayOf("image_path"),
            selection,
            selectionArgs,
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    cursor.getStringOrNull("image_path")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }

    private fun deleteImageFiles(paths: List<String>) {
        paths.distinct().forEach { path ->
            runCatching {
                File(path).takeIf { it.exists() }?.delete()
            }
        }
    }
}

private fun android.database.Cursor.toHistoryItem(): HistoryItem = HistoryItem(
    clientItemId = getString(getColumnIndexOrThrow("client_item_id")),
    contentKind = ContentKind.valueOf(getString(getColumnIndexOrThrow("content_kind"))),
    textContent = getStringOrNull("text_content")?.takeIf { it.isNotBlank() },
    imagePath = getStringOrNull("image_path"),
    mimeType = getStringOrNull("mime_type"),
    skipReason = getStringOrNull("skip_reason"),
    contentHash = getString(getColumnIndexOrThrow("content_hash")),
    clientCreatedAt = getString(getColumnIndexOrThrow("client_created_at")),
    syncState = SyncState.valueOf(getString(getColumnIndexOrThrow("sync_state"))),
    serverCursor = getLongOrNull("server_cursor"),
    serverItemId = getLongOrNull("server_item_id"),
)

private fun android.database.Cursor.getStringOrNull(columnName: String): String? {
    val index = getColumnIndexOrThrow(columnName)
    return if (isNull(index)) null else getString(index)
}

private fun android.database.Cursor.getLongOrNull(columnName: String): Long? {
    val index = getColumnIndexOrThrow(columnName)
    return if (isNull(index)) null else getLong(index)
}
