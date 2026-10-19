package com.example

import com.example.data.prefs.PreferenceManager
import com.example.data.prefs.ServerUrlValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side tests for the configurable server URL rules: no baked-in default,
 * safe normalization (schemes, trailing slashes, dashboard path segments),
 * understandable rejection messages, and correct API/WS endpoint derivation.
 */
class ServerUrlValidationTest {

    @Test
    fun emptyAddress_isNotConfigured_withGuidanceMessage() {
        val v = PreferenceManager.validateServerUrl("")
        assertFalse(v.isValid)
        assertNotNull(v.error)
        assertTrue("error should explain what to enter", v.error!!.contains("Monitory backend"))
        assertEquals("", PreferenceManager.DEFAULT_SERVER_URL)
    }

    @Test
    fun validHttpsUrl_isAccepted_andNormalized() {
        val v = PreferenceManager.validateServerUrl("https://monitory.example.com/")
        assertTrue(v.isValid)
        assertEquals("https://monitory.example.com", v.normalized)
    }

    @Test
    fun dashboardPathSegments_areStripped() {
        for (input in listOf(
            "https://monitory.example.com/admin/nodes",
            "https://monitory.example.com/admin",
            "https://monitory.example.com/dashboard",
            "https://monitory.example.com/admin/nodes/"
        )) {
            val v = PreferenceManager.validateServerUrl(input)
            assertTrue("should accept $input", v.isValid)
            assertEquals("https://monitory.example.com", v.normalized)
        }
    }

    @Test
    fun customSubPath_isPreserved_withoutDoubleSlashes() {
        val v = PreferenceManager.validateServerUrl("https://example.com/monitory/")
        assertTrue(v.isValid)
        assertEquals("https://example.com/monitory", v.normalized)
    }

    @Test
    fun schemeIsInferred_forIpAndHostname() {
        val ip = PreferenceManager.validateServerUrl("192.168.1.10:3000")
        assertTrue(ip.isValid)
        assertEquals("http://192.168.1.10:3000", ip.normalized)

        val host = PreferenceManager.validateServerUrl("monitory.example.com")
        assertTrue(host.isValid)
        assertEquals("https://monitory.example.com", host.normalized)
    }

    @Test
    fun nonHttpSchemes_areRejected_withMessage() {
        val v = PreferenceManager.validateServerUrl("ftp://monitory.example.com")
        assertFalse(v.isValid)
        assertNotNull(v.error)
        assertTrue(v.error!!.contains("http"))

        val js = PreferenceManager.validateServerUrl("javascript:alert(1)")
        assertFalse(js.isValid)
    }

    @Test
    fun spaces_query_fragments_userInfo_areRejected() {
        assertFalse(PreferenceManager.validateServerUrl("https://monitory example.com").isValid)
        assertFalse(PreferenceManager.validateServerUrl("https://monitory.example.com?x=1").isValid)
        assertFalse(PreferenceManager.validateServerUrl("https://monitory.example.com#frag").isValid)
        assertFalse(PreferenceManager.validateServerUrl("https://user:pass@monitory.example.com").isValid)
    }

    @Test
    fun invalidUrls_produceUnderstandableErrors() {
        val noHost = PreferenceManager.validateServerUrl("https://")
        assertFalse(noHost.isValid)
        assertNotNull(noHost.error)
    }

    @Test
    fun endpoints_deriveFromBaseUrl_withoutDuplicatingPaths() {
        val base = "https://monitory.example.com"
        assertEquals("https://monitory.example.com/", PreferenceManager.deriveApiBaseUrl(base))
        assertEquals("https://monitory.example.com/", PreferenceManager.deriveApiBaseUrl("$base/"))
        assertEquals("wss://monitory.example.com/ws/device", PreferenceManager.deriveWsDeviceUrl(base))
        assertEquals("ws://192.168.1.10:3000/ws/device", PreferenceManager.deriveWsDeviceUrl("http://192.168.1.10:3000"))
        assertEquals("wss://example.com/monitory/ws/device", PreferenceManager.deriveWsDeviceUrl("https://example.com/monitory"))
    }

    @Test
    fun validationResult_isEitherValidOrError_neverBoth() {
        val ok: ServerUrlValidation = PreferenceManager.validateServerUrl("https://a.example")
        assertNull(ok.error)
        val bad = PreferenceManager.validateServerUrl("ht!tp://bad url")
        assertNull(bad.normalized)
        assertFalse(bad.isValid)
    }
}
