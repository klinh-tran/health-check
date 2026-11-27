package com.example.healthcheck.core.color

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetCodecTest {
    @Test fun roundTrip() {
        val presets = listOf(ColorPreset("Ocean", ColorChoice(200f, 70f, 40f)), ColorPreset("Rose, warm", ColorChoice(340f, 60f, 10f)))
        assertEquals(presets, PresetCodec.decode(PresetCodec.encode(presets)))
    }

    @Test fun capsAtFiveAndToleratesGarbage() {
        val many = (1..8).map { ColorPreset("P$it", ColorChoice(it * 10f, 50f, 50f)) }
        assertEquals(5, PresetCodec.decode(PresetCodec.encode(many)).size)
        assertEquals(emptyList<ColorPreset>(), PresetCodec.decode("nonsense"))
        assertEquals(emptyList<ColorPreset>(), PresetCodec.decode(null))
    }

    @Test fun cleansNames() {
        assertEquals("ab", PresetCodec.cleanName("  a\u001Fb \n"))
        assertEquals(PresetCodec.MAX_NAME_LENGTH, PresetCodec.cleanName("x".repeat(100)).length)
    }
}
