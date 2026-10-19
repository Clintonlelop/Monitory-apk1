package com.example

import com.example.data.model.DeviceTelemetry
import com.example.data.model.DevicePermissionsStatus
import com.example.ui.viewmodel.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun telemetry_creation_isCorrect() {
        val telemetry = DeviceTelemetry(
            deviceId = "test_dev_123",
            deviceName = "Pixel 8 Pro",
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            osVersion = "14",
            sdkVersion = 34,
            appVersion = "1.0",
            batteryLevel = 85,
            isCharging = true,
            storageAvailableBytes = 10000000000L,
            storageTotalBytes = 128000000000L,
            ramAvailableBytes = 4000000000L,
            ramTotalBytes = 8000000000L,
            networkType = "WIFI",
            wifiSsid = "OfficeWiFi",
            isWifiConnected = true,
            uptimeMillis = 500000L
        )
        assertEquals("test_dev_123", telemetry.deviceId)
        assertEquals("Pixel 8 Pro", telemetry.deviceName)
        assertEquals(85, telemetry.batteryLevel)
        assertEquals(true, telemetry.isCharging)
        assertEquals("WIFI", telemetry.networkType)
    }

    @Test
    fun permissions_defaults_areValid() {
        val perms = DevicePermissionsStatus(
            location = true,
            notificationAccess = true,
            filesAccess = false,
            camera = false,
            microphone = false,
            usageAccess = false,
            screenSharing = false
        )
        assertEquals(true, perms.location)
        assertEquals(false, perms.filesAccess)
        assertEquals(false, perms.contacts)
    }

    @Test
    fun screen_states_areDistinct() {
        val s1: Screen = Screen.Dashboard
        val s2: Screen = Screen.Privacy
        val s3: Screen = Screen.Auth
        assertNotNull(s1)
        assertNotNull(s2)
        assertNotNull(s3)
        assertEquals(false, s1 == s2)
    }
}
