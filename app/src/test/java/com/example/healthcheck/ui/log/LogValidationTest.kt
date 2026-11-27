package com.example.healthcheck.ui.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LogValidationTest {
    @Test fun steps() {
        assertEquals(null to null, LogValidation.steps("  "))
        assertEquals(0 to null, LogValidation.steps("0"))
        assertEquals(100_000 to null, LogValidation.steps("100000"))
        assertNotNull(LogValidation.steps("100001").second)
        assertNotNull(LogValidation.steps("12.5").second)
        assertNotNull(LogValidation.steps("-3").second)
    }

    @Test fun sleep() {
        assertEquals(null to null, LogValidation.sleep(""))
        assertEquals(7.5 to null, LogValidation.sleep("7.5"))
        assertEquals(7.5 to null, LogValidation.sleep("7,5")) // comma decimal keyboards
        assertEquals(24.0 to null, LogValidation.sleep("24"))
        assertEquals(0.0 to null, LogValidation.sleep("0"))
        assertNotNull(LogValidation.sleep("24.1").second)
        assertNotNull(LogValidation.sleep("7.25").second) // one decimal only
        assertNotNull(LogValidation.sleep("abc").second)
        assertNull(LogValidation.sleep("abc").first)
    }

    @Test fun deltaFormatting() {
        assertEquals("−6 vs usual", LogFormat.delta(-6.2, 0))
        assertEquals("+0.5 vs usual", LogFormat.delta(0.46, 1))
        assertEquals("Same as usual", LogFormat.delta(0.2, 0))
    }
}
