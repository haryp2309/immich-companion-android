package app.immichcompanion.picker

import org.junit.Assert.assertEquals
import org.junit.Test

class PickRequestTest {
    @Test fun mimeFilters() {
        assertEquals(listOf("image/"), PickRequest.mimePrefixes(listOf("image/*")))
        assertEquals(listOf("image/png", "video/"), PickRequest.mimePrefixes(listOf("image/png", "video/*")))
        assertEquals(emptyList<String>(), PickRequest.mimePrefixes(listOf("*/*")))
        assertEquals(emptyList<String>(), PickRequest.mimePrefixes(listOf("image/*", "application/pdf")))
    }
}
