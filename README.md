# Clipboard River Android

English · [简体中文](README.zh-CN.md)

A Kotlin and Jetpack Compose clipboard client for Android.

## Features

- Send clipboard text manually, with a configurable length range of 2–1024 characters by default.
- Receive text while the app is in the foreground and copy it to the system clipboard.
- Send a single image or other file through the Android share menu.
- Save received images to `Pictures/ClipboardRiver` and other files to `Download/ClipboardRiver`, with an open action.
- Notification shortcuts for sending the clipboard or opening a receive window.
- A floating receive window that waits up to 60 seconds and closes after showing its result.
- A home screen and separate settings page, English and Simplified Chinese, and system light/dark themes.

There is no local history, offline queue, history import, or recent-50 synchronization. Events missed while disconnected are not recovered.

## Getting started

1. Open enrollment on the server and obtain its current five-minute code.
2. Enter the server address, optional device nickname, and code in the app.
3. Send clipboard text from the home screen. To send an image or file, select Clipboard River from the Android share menu.
4. Keep the app visible to receive, or enable notifications and the overlay permission to receive through a floating window.

Home-screen sending and Android sharing do not require overlay permission. Notification actions require it; the settings page provides an explicit permission entry.

Text sending preserves the previous behavior: trim surrounding whitespace, then apply the configured Kotlin string-length range. Received text is preserved and is not uploaded again.

## Protocol

The client implements registration, device profile, single text upload, file upload/download, and WebSocket events.

- Registration sends the installation UUID, enrollment code, nickname, app name, and version. Existing credentials and connection settings are replaced only after successful registration.
- Profile requests update only the app name/version and refresh server settings.
- WebSocket sends only the initial `hello`. The connection is marked ready after `hello_ack`.
- No application heartbeat or `ack`, legacy cursors, capability declarations, or `client_item_id`.
- Ignore `clipboard.sync_changed` and unknown event types.
- A foreground connection makes at most five attempts, with a ten-second handshake timeout. It is released after the last foreground owner leaves.
- HTTP 401 stops retries and requests registration again.
- Uploads have no idempotency key and are never automatically replayed. Unknown outcomes are shown explicitly. Upload success confirms server acceptance, not delivery to other devices.
- Upload size settings distinguish a positive byte limit, `0` for no application-level limit, and `-1` for disabled file uploads. This does not restrict downloads.

## Android and storage

One ongoing notification provides shortcuts and is reused as the foreground-service notification during floating operations. It remains as the shortcut after the operation ends. Android may separately show its own overlay notice. There is no indefinite background receiving or clipboard monitoring.

Preferences use DataStore; device credentials use EncryptedSharedPreferences. Uploads use the app cache `transfers` directory, with cleanup after completion, failure, cancellation, and the next startup.

Old history databases and files remain untouched and are no longer accessed. Downloaded public files remain available. Connection state and the latest result are held only in memory.

## Build and validation

Requires Android 12L/API 32 or newer and Android SDK 35. The Gradle daemon uses JDK 21; source compatibility targets Java 17.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
