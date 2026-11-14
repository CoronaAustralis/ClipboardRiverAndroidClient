package com.clipboardriver.client.net

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ClientProtocolTest {
    private val json get() = ClientProtocol.json
    private val receipt = """{"created":true,"item":{"item_id":12,"content_kind":"text","upload_kind":"realtime","mime_type":"","received_at":"2026-04-10T08:00:00Z"}}"""

    @Test fun registrationProfileAndHelloContainOnlyNewProtocolFields() {
        val registration = json.parseToJsonElement(json.encodeToString(RegisterRequest.serializer(),
            RegisterRequest("uuid", "code", "Phone", "Clipboard River Android", "1.0"))).jsonObject
        assertEquals(setOf("device_uuid", "enrollment_code", "nickname", "app_name", "app_version"), registration.keys)
        val profile = json.parseToJsonElement(json.encodeToString(DeviceProfileRequest.serializer(),
            DeviceProfileRequest("App", "1.0"))).jsonObject
        assertEquals(setOf("app_name", "app_version"), profile.keys)
        val hello = json.parseToJsonElement(json.encodeToString(HelloMessage.serializer(),
            HelloMessage(HelloPayload("secret", "uuid")))).jsonObject
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("device_token", "device_uuid"), hello["payload"]!!.jsonObject.keys)
    }

    @Test fun compactUploadReceiptDoesNotRequireContentOrCursor() {
        val result = json.decodeFromString(UploadReceipt.serializer(), receipt)
        assertTrue(result.created)
        assertEquals(12L, result.item.itemId)
        assertEquals("realtime", result.item.uploadKind)
    }

    @Test fun syncChangedAndUnknownEventsAreIgnoredRegardlessOfPayloadShape() {
        assertNull(ClientProtocol.decodeEvent("""{"type":"clipboard.sync_changed","payload":{"item_ids":[200,100]}}"""))
        assertNull(ClientProtocol.decodeEvent("""{"type":"future.event","payload":{"arbitrary":true}}"""))
        assertNull(ClientProtocol.decodeEvent("""not-json"""))
    }

    @Test fun fullTextAndFileEventsUseItemIdAndAcceptNullableSource() {
        val text = ClientProtocol.decodeEvent("""{
          "type":"clipboard.created","item":{
            "item_id":15,"source_device_id":null,"content_kind":"text","upload_kind":"realtime",
            "mime_type":"","byte_size":11,"char_count":11,"client_created_at":"2026-04-09T10:23:00Z",
            "received_at":"2026-04-10T08:04:00Z","text_content":"hello world"
          }}""") as ServerEvent.ClipboardCreated
        assertEquals(15L, text.item.itemId)
        assertEquals("hello world", text.item.textContent)
        val file = ClientProtocol.decodeEvent("""{
          "type":"clipboard.created","item":{
            "item_id":16,"content_kind":"file","upload_kind":"realtime","mime_type":"image/png",
            "received_at":"2026-04-10T08:05:00Z","blob_name":"origin name.png"
          }}""") as ServerEvent.ClipboardCreated
        assertEquals(com.clipboardriver.client.model.ContentKind.FILE, file.item.contentKind)
        assertEquals("image/png", file.item.mimeType)
        assertEquals("origin name.png", file.item.blobName)
        assertNull(ClientProtocol.decodeEvent("""{"type":"clipboard.created","item":{"item_id":17,
          "content_kind":"future","upload_kind":"realtime","received_at":"2026-04-10T08:05:00Z"}}"""))
    }

    @Test fun settingsPreserveDisabledUnlimitedAndPositiveLimits() {
        for (limit in longArrayOf(-1, 0, 5_242_880)) {
            val settings = json.decodeFromString(SettingsPayload.serializer(),
                """{"file_max_bytes":""" + limit + ""","text_batch_limit":200}""").toSettings()
            assertEquals(limit, settings.fileMaxBytes)
        }
        assertThrows(IllegalArgumentException::class.java) { SettingsPayload(-2, 200).toSettings() }
    }

    @Test fun httpTextUsesBearerAndOnlyRealtimeUploadFields() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor {
            captured = it.request()
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(receipt.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = ApiClient(client).uploadText("https://fixture.invalid", "secret",
            TextUploadRequest("2026-04-09T10:20:30Z", "hello world"))
        assertEquals(12L, result.item.itemId)
        assertEquals("/api/v1/client/items/text", captured!!.url.encodedPath)
        assertEquals("Bearer secret", captured!!.header("Authorization"))
        val buffer = Buffer()
        captured!!.body!!.writeTo(buffer)
        val request = json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(setOf("text_content", "upload_kind", "client_created_at"), request.keys)
        assertEquals("hello world", request["text_content"]!!.jsonPrimitive.content)
    }

    @Test fun multipartContainsOneFileWithoutLegacyIdentity() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor {
            captured = it.request()
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(receipt.replace("\"text\"", "\"file\"").toResponseBody()).build()
        }.build()
        val file = File.createTempFile("clipboard-river", ".txt")
        try {
            file.writeText("fixture")
            ApiClient(client).uploadFile("https://fixture.invalid", "secret",
                FileUploadRequest("2026-04-09T10:20:30Z", "text/plain", "origin.txt", file))
            val body = captured!!.body as MultipartBody
            assertEquals(4, body.parts.size)
            val buffer = Buffer()
            body.writeTo(buffer)
            val raw = buffer.readUtf8()
            assertTrue(raw.contains("filename=\"origin.txt\""))
            assertTrue(raw.contains("realtime"))
            assertFalse(raw.contains("client_item_id"))
        } finally { file.delete() }
    }

    @Test fun errorsExposeServerDescriptionAndBlobResponseIsStreamed() = runBlocking {
        val denied = OkHttpClient.Builder().addInterceptor {
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                .body("""{"error":"enrollment closed"}""".toResponseBody()).build()
        }.build()
        try {
            ApiClient(denied).register("https://fixture.invalid", RegisterRequest("id", "code", "", "App", "1"))
            fail("Expected registration to fail")
        } catch (failure: ApiException) {
            assertEquals(400, failure.code)
            assertEquals("enrollment closed", failure.message)
        }
        val blobClient = OkHttpClient.Builder().addInterceptor {
            assertEquals("/api/v1/client/items/16/blob", it.request().url.encodedPath)
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Disposition", "inline; filename*=UTF-8''hello%20world%2B.txt")
                .body("streamed data".toResponseBody("text/plain".toMediaType())).build()
        }.build()
        ApiClient(blobClient).downloadBlob("https://fixture.invalid", "secret", 16).use {
            assertEquals("hello world+.txt", it.fileName)
            assertEquals("streamed data", it.stream.reader().readText())
        }
    }
}