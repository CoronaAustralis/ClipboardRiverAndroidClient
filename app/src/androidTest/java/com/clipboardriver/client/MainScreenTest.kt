package com.clipboardriver.client

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.clipboardriver.client.model.*
import com.clipboardriver.client.ui.*
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
    @get:Rule val compose = createComposeRule()
    private var sent = 0
    private var saved: List<Any>? = null

    private fun render(state: MainUiState, locale: String = "en", dark: Boolean = false) {
        compose.setContent {
            val context = LocalContext.current
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(config),
                LocalConfiguration provides config,
            ) {
                ClipboardRiverAppTheme(darkTheme = dark) {
                    MainScreen(
                        uiState = state, overlayPermissionGranted = false, notificationPermissionGranted = true,
                        onFormChanged = {}, onRegister = {}, onSendClipboard = { sent++ },
                        onReceive = {}, onReconnect = {}, onSignOut = {},
                        onSaveSettings = { send, receive, min, max -> saved = listOf(send, receive, min, max) },
                        onOverlayPermission = {}, onNotificationPermission = {}, onOpenFile = { _, _ -> }, onClearBanner = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun home() = MainUiState(
        initializing = false, isRegistered = true,
        config = AppConfig(serverBaseUrl = "https://example.invalid", deviceUuid = "fixture", deviceId = 2, nickname = "MuMu",
            serverSettings = ServerSettings(5_242_880)),
        realtimeStatus = RealtimeStatus(ConnectionState.CONNECTED),
    )

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun englishOnboardingAndKeyboardLayout() {
        render(MainUiState(initializing = false))
        compose.onNodeWithText("Connect a device").assertIsDisplayed()
        compose.onNodeWithText("Connect", substring = false).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Server address").performScrollTo().performClick()
        screenshot("onboarding-en-keyboard")
        Espresso.closeSoftKeyboard()
        screenshot("onboarding-en-light")
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Transfer preferences").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Connect a device").assertIsDisplayed()
        compose.onNodeWithText("History", substring = false).assertDoesNotExist()
    }

    @Test fun chineseHomeAndSettingsNavigation() {
        render(home(), "zh-Hans")
        compose.onNodeWithText("已连接").assertIsDisplayed()
        compose.onNodeWithText("发送剪贴板").performClick()
        assertEquals(1, sent)
        screenshot("home-zh-light")
        compose.onNodeWithContentDescription("设置").performClick()
        screenshot("settings-zh-light")
        compose.onNodeWithText("收发偏好").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("通知栏快捷入口").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("发送剪贴板").assertIsDisplayed()
        compose.onNodeWithText("历史", substring = false).assertDoesNotExist()
    }

    @Test fun settingsRejectInvalidLengthsAndSavePreferences() {
        render(home())
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Minimum", substring = false).performScrollTo().performTextReplacement("0")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Save preferences").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Minimum", substring = false).performScrollTo().performTextReplacement("2")
        compose.onNodeWithText("Maximum", substring = false).performScrollTo().performTextReplacement("32")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("Allow sending").performScrollTo().performClick()
        compose.onNodeWithText("Save preferences").performScrollTo().performClick()
        assertEquals(listOf(false, true, 2, 32), saved)
    }

    @Test fun darkHomeUsesThemeAcrossAllCards() {
        render(home(), dark = true)
        compose.onNodeWithText("Connected", substring = false).assertIsDisplayed()
        screenshot("home-en-dark")
    }

    @Test fun chineseDarkOnboarding() {
        render(MainUiState(initializing = false), "zh-Hans", true)
        compose.onNodeWithText("连接设备").assertIsDisplayed()
        screenshot("onboarding-zh-dark")
    }

    @Test fun expiredCredentialReturnsToRegistration() {
        render(MainUiState(initializing = false, realtimeStatus = RealtimeStatus(ConnectionState.AUTH_REQUIRED)))
        compose.onNodeWithText("Device credential is invalid. Register again to continue.").assertIsDisplayed()
        compose.onNodeWithText("Connect a device").assertIsDisplayed()
        compose.onNodeWithText("Send clipboard", substring = false).assertDoesNotExist()
    }
}