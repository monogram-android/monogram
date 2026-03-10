package org.monogram.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "org.monogram",
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()

        // Wait for the app to initialize
        device.wait(Until.hasObject(By.pkg("org.monogram")), 10_000)
        device.waitForIdle()

        // Scroll through the chat list
        val chatList = device.findObject(By.desc("ChatList"))
        chatList?.fling(Direction.DOWN)
        device.waitForIdle()
        chatList?.fling(Direction.UP)
        device.waitForIdle()

        // Click on the first chat
        val firstChat = device.findObject(By.desc("ChatTitle"))
        firstChat?.click()

        // Wait for the chat to open
        device.wait(Until.hasObject(By.desc("ChatContent")), 5_000)
        device.waitForIdle()

        // Scroll through the chat messages
        val chatMessages = device.findObject(By.desc("ChatMessages"))
        chatMessages?.fling(Direction.DOWN)
        device.waitForIdle()
        chatMessages?.fling(Direction.UP)
        device.waitForIdle()

        // Go back to the chat list
        device.pressBack()
        device.waitForIdle()

        // Open settings
        val settingsButton = device.findObject(By.desc("Settings"))
        settingsButton?.click()

        // Wait for the settings to open
        device.wait(Until.hasObject(By.desc("SettingsContent")), 5_000)
        device.waitForIdle()

        // Scroll through the settings
        val settingsList = device.findObject(By.desc("SettingsList"))
        settingsList?.fling(Direction.DOWN)
        device.waitForIdle()
        settingsList?.fling(Direction.UP)
        device.waitForIdle()

        // Go back to the chat list
        device.pressBack()
        device.waitForIdle()
    }
}