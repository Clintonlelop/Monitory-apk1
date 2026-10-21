package com.example

import com.example.data.model.RemoteCommand
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCommandMoshiTest {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun remoteCommand_usesGeneratedAdapter_andRoundTrips() {
        val adapter = moshi.adapter(RemoteCommand::class.java)

        assertEquals(
            "com.example.data.model.RemoteCommandJsonAdapter",
            adapter::class.java.name
        )

        val json = """
            {
              "commandId": "cmd-123",
              "deviceId": "device-456",
              "commandType": "GET_STATUS",
              "parameters": {"detail": "full"},
              "status": "PENDING",
              "timestamp": 1710000000000
            }
        """.trimIndent()

        val decoded = adapter.fromJson(json)
        assertNotNull(decoded)
        assertEquals("cmd-123", decoded!!.commandId)
        assertEquals("device-456", decoded.deviceId)
        assertEquals("GET_STATUS", decoded.commandType)
        assertEquals("full", decoded.parameters!!["detail"])

        val encoded = adapter.toJson(decoded.copy(status = "COMPLETED", result = "ok"))
        assertTrue(encoded.contains("\"status\":\"COMPLETED\""))
        assertTrue(encoded.contains("\"result\":\"ok\""))
    }
}
