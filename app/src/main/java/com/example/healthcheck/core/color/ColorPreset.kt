package com.example.healthcheck.core.color

data class ColorPreset(val name: String, val choice: ColorChoice)

/** Serialises presets into one string for DataStore without needing a JSON library. */
object PresetCodec {
    const val MAX_PRESETS = 5
    const val MAX_NAME_LENGTH = 24

    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'

    fun cleanName(raw: String): String =
        raw.filter { !it.isISOControl() }.trim().take(MAX_NAME_LENGTH)

    fun encode(presets: List<ColorPreset>): String =
        presets.take(MAX_PRESETS).joinToString(RECORD.toString()) { p ->
            listOf(cleanName(p.name), p.choice.hue, p.choice.vibrancy, p.choice.tint).joinToString(FIELD.toString())
        }

    fun decode(text: String?): List<ColorPreset> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.split(RECORD).mapNotNull { rec ->
            val f = rec.split(FIELD)
            if (f.size != 4) return@mapNotNull null
            val h = f[1].toFloatOrNull() ?: return@mapNotNull null
            val v = f[2].toFloatOrNull() ?: return@mapNotNull null
            val t = f[3].toFloatOrNull() ?: return@mapNotNull null
            val name = cleanName(f[0])
            if (name.isEmpty()) null else ColorPreset(name, ColorChoice(h, v, t).clamped())
        }.take(MAX_PRESETS)
    }
}
