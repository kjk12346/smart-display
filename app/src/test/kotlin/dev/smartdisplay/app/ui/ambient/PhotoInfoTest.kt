package dev.smartdisplay.app.ui.ambient

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoInfoTest {
    @Test
    fun `EXIF dates are read, and missing or zeroed ones are unknown`() {
        assertEquals(LocalDate.of(2019, 6, 14), parseExifDate("2019:06:14 18:30:05"))
        assertEquals(LocalDate.of(2019, 6, 14), parseExifDate(" 2019:06:14"))
        assertNull(parseExifDate("0000:00:00 00:00:00"))
        assertNull(parseExifDate("    :  :     :  :  "))
        assertNull(parseExifDate(""))
        assertNull(parseExifDate(null))
    }

    @Test
    fun `at home the state is given, abroad the country`() {
        val austin = Place("Austin", "Travis County", "Texas", "United States", "US")
        assertEquals("Austin, Texas", placeName(austin, "US"))
        val lisbon = Place("Lisbon", "Lisbon", "Lisbon", "Portugal", "PT")
        assertEquals("Lisbon, Portugal", placeName(lisbon, "US"))
        // Without a home country, everywhere is abroad.
        assertEquals("Austin, United States", placeName(austin, ""))
    }

    @Test
    fun `the county stands in for a town, and nothing repeats`() {
        val park = Place(null, "Mariposa County", "California", "United States", "US")
        assertEquals("Mariposa County, California", placeName(park, "US"))
        assertEquals("Singapore", placeName(Place("Singapore", null, null, "Singapore", "SG"), "US"))
        assertEquals("Portugal", placeName(Place(null, null, null, "Portugal", "PT"), "US"))
        assertNull(placeName(Place(null, null, null, null, null), "US"))
    }
}
