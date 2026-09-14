package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PlacesTest {

    @Test
    fun `things that are not places`() {
        for (text in listOf(
            "01712345678", "14/09/2026", "12 St", "Road 12", "Floor 3", "Dr Rahman, 5 pm", "I have 3 books",
            "5 star hotel", "example.com/road/12", "meet at the market at 5 pm", "12.99, 14.99", "23, 90",
            "91.0, 10.0", "23.8103, 190.4125", "House 12\nRoad 5", "x".repeat(121),
        )) {
            assertNull(text, Places.parseCoordinates(text))
            assertFalse(text, Places.looksLikeAddress(text))
        }
    }

    @Test
    fun `coordinates in every common shape`() {
        val dhaka = LatLng(23.8103, 90.4125)
        for (text in listOf("23.8103, 90.4125", "23.8103 90.4125", "(23.8103, 90.4125)", "23.8103° N, 90.4125° E", "90.4125 E 23.8103 N")) {
            assertEquals(text, dhaka, Places.parseCoordinates(text))
        }
        val dms = Places.parseCoordinates("23°48'37\"N 90°24'45\"E")!!
        assertEquals(23.8103, dms.lat, 0.001)
        assertEquals(90.4125, dms.lng, 0.001)
        assertEquals(LatLng(23.8, 90.4), Places.parseCoordinates("23°48'N 90°24'E"))
        assertEquals(LatLng(-33.8688, 151.2093), Places.parseCoordinates("-33.8688, 151.2093"))
    }

    @Test
    fun `addresses by keyword, abbreviation shape and postcode`() {
        for (text in listOf(
            "House 12, Road 5, Dhanmondi, Dhaka 1209", "12 Baker Street", "221B Baker St, London NW1 6XE",
            "Flat 4B, 27 Green Lane", "Plot 15, Sector 7, Uttara", "বাসা ১২, রোড ৫, ধানমন্ডি", "Gulshan 2, Dhaka 1212",
            "Apt 4, 100 Main Rd",
        )) {
            assertTrue(text, Places.looksLikeAddress(text))
        }
    }

    @Test
    fun `geo uris are locale-proof and encoded`() {
        val default = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("geo:-33.8688,151.2093?q=-33.8688,151.2093", Places.geoUri(Place.Coordinates(LatLng(-33.8688, 151.2093))))
            assertEquals("geo:23.8,90.4?q=23.8,90.4", Places.geoUri(Place.Coordinates(LatLng(23.8, 90.4))))
        } finally {
            Locale.setDefault(default)
        }
        assertEquals("geo:0,0?q=12+Baker+Street", Places.geoUri(Place.Address("12 Baker Street")))
        assertTrue(Places.geoUri(Place.Address("রোড ৫")).startsWith("geo:0,0?q=%E0%A6"))
    }
}
