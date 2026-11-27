package com.home.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun readsTheVersionFromTheReleaseAddress() {
        assertEquals("1.4.0", Updates.versionIn("https://github.com/o/r/releases/tag/v1.4.0"))
        assertNull(Updates.versionIn("https://github.com/o/r/releases"))
        assertNull(Updates.versionIn("https://github.com/o/r/releases/tag/vNext"))
        assertNull(Updates.versionIn(""))
    }

    @Test fun forgetsAnAnswerFromAnotherVersionOrFormat() {
        assertEquals("1.4.0", Updates.remembered("1.4.0 1.3.9", "1.3.9"))
        assertNull(Updates.remembered("1.4.0 1.3.9", "1.4.0"))
        assertNull(Updates.remembered("1.4.0", "1.4.0"))
        assertNull(Updates.remembered("", "1.4.0"))
        assertNull(Updates.remembered("junk 1.4.0", "1.4.0"))
    }

    @Test fun comparesNumbersNotText() {
        assertTrue(Updates.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updates.isNewer("1.4.1", "1.4"))
        assertFalse(Updates.isNewer("1.4.0", "1.4.0"))
        assertFalse(Updates.isNewer("1.3.2", "1.4.0"))
    }
}
