package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MeteredLinkTest {

    @Test
    fun `metered Wi-Fi is not called mobile data`() {
        assertEquals(MeteredLink.WIFI, meteredLinkOf(cellular = false, vpn = false, wifi = true))
    }

    @Test
    fun `a VPN over Wi-Fi names the VPN`() {
        assertEquals(MeteredLink.VPN, meteredLinkOf(cellular = false, vpn = true, wifi = true))
    }

    @Test
    fun `a VPN over mobile data still spends mobile data`() {
        assertEquals(MeteredLink.MOBILE, meteredLinkOf(cellular = true, vpn = true, wifi = false))
    }

    @Test
    fun `mobile data alone is mobile data`() {
        assertEquals(MeteredLink.MOBILE, meteredLinkOf(cellular = true, vpn = false, wifi = false))
    }

    @Test
    fun `no known transport falls back to the neutral words`() {
        assertEquals(MeteredLink.OTHER, meteredLinkOf(cellular = false, vpn = false, wifi = false))
    }
}
