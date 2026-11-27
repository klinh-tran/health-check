package com.example.healthcheck.core.companion

import com.example.healthcheck.core.scoring.StrainLabel
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

enum class CompanionMood { GREAT, NORMAL, TIRED, MEASURING, SAVED }

enum class AnimationLevel(val label: String) { OFF("Off"), CALM("Calm"), PLAYFUL("Playful") }

enum class EyeState { OPEN, CLOSED, HAPPY }

data class Accessories(
    val orange: Boolean = false,
    val hat: Boolean = false,
    val sunglasses: Boolean = false,
    val bow: Boolean = false,
) {
    companion object {
        val NONE = Accessories()
        val ALL_SINGLE = listOf(
            "Orange" to Accessories(orange = true),
            "Party hat" to Accessories(hat = true),
            "Sunglasses" to Accessories(sunglasses = true),
            "Bow" to Accessories(bow = true),
        )
    }
}

/** One stage pixel, colour 0xAARRGGBB. */
data class StagePixel(val x: Int, val y: Int, val color: Int)

object CompanionStage {
    const val COLUMNS = 24
    const val ROWS = 22
    const val SPRITE_X = 4
    const val SPRITE_Y = 5
    const val TICK_MS = 100L

    const val EYE = 0xFF1B1F27.toInt()
    const val CHEEK = 0xFFFF9FB0.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val PINK = 0xFFFF6B81.toInt()
    const val RED = 0xFFFF2D55.toInt()
    const val HEART_HIGHLIGHT = 0xFFFFC2CD.toInt()
    const val YELLOW = 0xFFFFD54A.toInt()
    const val PALE_YELLOW = 0xFFFFF3B0.toInt()
    const val ORANGE = 0xFFFF9F1C.toInt()
    const val ORANGE_HIGHLIGHT = 0xFFFFC266.toInt()
    const val ORANGE_SHADE = 0xFFE8890C.toInt()
    const val LEAF = 0xFF4CAF50.toInt()
    const val LEAF_DARK = 0xFF2E7D32.toInt()
    const val BOW_KNOT = 0xFFC2185B.toInt()
    const val Z_COLOR = 0xFF8EA0C0.toInt()
    const val SWEAT = 0xFF6EC6FF.toInt()

    val HEART: List<String> = listOf(".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X...")
    val SMALL_HEART: List<String> = listOf(".X.X.", "XXXXX", ".XXX.", "..X..")

    fun inside(x: Int, y: Int) = x in 0 until COLUMNS && y in 0 until ROWS
}

object CompanionMotion {
    /** Home mood from today's strain label; no verdict yet is "normal". */
    fun moodFor(label: StrainLabel?): CompanionMood = when (label) {
        StrainLabel.LOW -> CompanionMood.GREAT
        StrainLabel.NORMAL -> CompanionMood.NORMAL
        StrainLabel.ELEVATED -> CompanionMood.TIRED
        null -> CompanionMood.NORMAL
    }

    /** Vertical offset in pixels (negative is up). */
    fun bob(mood: CompanionMood, level: AnimationLevel, t: Int): Int {
        if (level == AnimationLevel.OFF) return 0
        return when (mood) {
            CompanionMood.GREAT, CompanionMood.SAVED -> {
                val a = if (level == AnimationLevel.PLAYFUL) 3 else 1
                val ph = t.mod(14)
                if (ph < 7) -(sin(ph / 7.0 * PI) * a).roundToInt() else 0
            }
            CompanionMood.NORMAL ->
                if (level == AnimationLevel.CALM) 0 else if (t.mod(20) < 10) 0 else -1
            CompanionMood.TIRED -> if (t.mod(30) < 15) 0 else 1
            CompanionMood.MEASURING -> if (t.mod(8) < 2) -1 else 0
        }
    }

    /** Every bob value a mood/level can produce (for bounds tests). */
    fun bobRange(mood: CompanionMood, level: AnimationLevel): Set<Int> = (0 until 840).map { bob(mood, level, it) }.toSet()

    fun eyeState(mood: CompanionMood, t: Int): EyeState = when (mood) {
        CompanionMood.GREAT, CompanionMood.SAVED -> EyeState.HAPPY
        CompanionMood.TIRED -> EyeState.CLOSED
        else -> if (isBlinking(t)) EyeState.CLOSED else EyeState.OPEN
    }

    /** Closed for 2 ticks every 36 ticks. */
    fun isBlinking(t: Int) = t.mod(36) >= 34

    /** Heart hug "beat": 3 ticks out of every 8. */
    fun isHeartBeat(t: Int) = t.mod(8) < 3
}

object CompanionText {
    fun bubble(mood: CompanionMood, voice: String): String = when (mood) {
        CompanionMood.GREAT -> "$voice Great recovery today!"
        CompanionMood.NORMAL -> "Steady day. Keep it up!"
        CompanionMood.TIRED -> "Take it easy tonight..."
        CompanionMood.MEASURING -> "Shh... counting beats"
        CompanionMood.SAVED -> "$voice Saved! Nice work."
    }

    fun buildingBaseline(days: Int, of: Int) = "Building your baseline, $days of $of days"

    fun describe(name: String, c: CompanionCharacter, mood: CompanionMood): String {
        val feeling = when (mood) {
            CompanionMood.GREAT -> "bouncing happily"
            CompanionMood.NORMAL -> "relaxed"
            CompanionMood.TIRED -> "sleepy"
            CompanionMood.MEASURING -> "listening to your heartbeat"
            CompanionMood.SAVED -> "celebrating"
        }
        return "$name the ${c.species}, $feeling"
    }

    const val MAX_NAME_LENGTH = 12

    fun cleanName(raw: String): String = raw.replace(Regex("[\\r\\n\\t]"), " ").take(MAX_NAME_LENGTH)
}

/** What to draw for one frame. */
data class FrameSpec(
    val character: CompanionCharacter,
    val mood: CompanionMood,
    val level: AnimationLevel,
    val accessories: Accessories = Accessories.NONE,
    val t: Int = 0,
    /** Measurement heart colour state; null uses the 8-tick fallback rhythm. */
    val measureHeartBright: Boolean? = null,
)

/** Builds the sprite, face, heart hug and accessories as stage pixels (later pixels draw on top). */
object CompanionRenderer {

    fun frame(f: FrameSpec): List<StagePixel> {
        val out = ArrayList<StagePixel>(400)
        val c = f.character
        val animated = f.level != AnimationLevel.OFF
        val bob = CompanionMotion.bob(f.mood, f.level, f.t)
        val droop = if (c.droopsWhenTired && f.mood == CompanionMood.TIRED) 1 else 0
        val ox = CompanionStage.SPRITE_X
        val oy = CompanionStage.SPRITE_Y + bob

        // Body. With droop, the stem (rows 9-15) draws first and the drooped head (rows 0-8) on top.
        val order = if (droop > 0) (9..15) + (0..8) else (0..15)
        for (row in order) {
            val line = c.rows[row]
            val dy = if (row <= 8) droop else 0
            for (col in 0 until 16) {
                val ch = line[col]
                if (ch == '.') continue
                var color = c.palette[ch] ?: continue
                if (c.twinklingAntenna && ch == 'a' && animated && (f.t / 4) % 2 == 1) color = CompanionStage.PALE_YELLOW
                out += StagePixel(ox + col, oy + row + dy, color)
            }
        }

        // Face (shifted by droop).
        val fy = oy + droop
        c.cheeks.forEach { out += StagePixel(ox + it.x, fy + it.y, CompanionStage.CHEEK) }
        c.nose.forEach { out += StagePixel(ox + it.x, fy + it.y, CompanionStage.EYE) }
        eyes(c, CompanionMotion.eyeState(f.mood, f.t)).forEach { out += StagePixel(ox + it.x, fy + it.y, CompanionStage.EYE) }
        mouth(c, f.mood).forEach { out += StagePixel(ox + it.x, fy + it.y, CompanionStage.EYE) }

        // Heart hug.
        if (f.mood == CompanionMood.GREAT || f.mood == CompanionMood.SAVED) {
            out += heartHug(c, bob, beat = animated && CompanionMotion.isHeartBeat(f.t))
        }

        accessories(c, f.accessories, bob, droop, out)

        // Tired sweat drop: 1x2, slides down 1 pixel every 6 ticks (restarts every 36 ticks).
        if (f.mood == CompanionMood.TIRED) {
            val fall = if (animated) (f.t.mod(36)) / 6 else 0
            val y = CompanionStage.SPRITE_Y + 3 + bob + fall
            out += StagePixel(ox + 12, y, CompanionStage.SWEAT)
            out += StagePixel(ox + 12, y + 1, CompanionStage.SWEAT)
        }

        // Measurement heart at stage (16,1).
        if (f.mood == CompanionMood.MEASURING) {
            val bright = if (!animated) false else f.measureHeartBright ?: (f.t.mod(8) < 3)
            pattern(CompanionStage.HEART, 16, 1, if (bright) CompanionStage.RED else CompanionStage.PINK, out)
        }
        return out
    }

    /**
     * Heart hug in stage pixels, centred on the sprite like the spec's 7x6 heart (7 wide from x 9, 5 wide from x 10).
     * On the beat it turns #FF2D55 and lifts 1 pixel. Highlight sits 1 pixel in from the top-left corner.
     */
    fun heartHug(c: CompanionCharacter, bob: Int, beat: Boolean): List<StagePixel> {
        val out = ArrayList<StagePixel>(32)
        val left = if (c.heart.small) 10 else 9
        val top = CompanionStage.SPRITE_Y + c.heart.top + bob - if (beat) 1 else 0
        val shape = if (c.heart.small) CompanionStage.SMALL_HEART else CompanionStage.HEART
        pattern(shape, left, top, if (beat) CompanionStage.RED else CompanionStage.PINK, out)
        out += StagePixel(left + 1, top + 1, CompanionStage.HEART_HIGHLIGHT)
        return out
    }

    /** Sprite-relative eye pixels. */
    fun eyes(c: CompanionCharacter, state: EyeState): List<SpritePoint> {
        val x = c.eyeX
        val y = c.eyeY
        val x2 = c.rightEyeX
        return when (state) {
            EyeState.OPEN -> listOf(SpritePoint(x, y), SpritePoint(x, y + 1), SpritePoint(x2, y), SpritePoint(x2, y + 1))
            EyeState.CLOSED -> listOf(
                SpritePoint(x - 1, y + 1), SpritePoint(x, y + 1),
                SpritePoint(x2, y + 1), SpritePoint(x2 + 1, y + 1),
            )
            EyeState.HAPPY -> listOf(
                SpritePoint(x - 1, y + 1), SpritePoint(x, y), SpritePoint(x + 1, y + 1),
                SpritePoint(x2 + 1, y + 1), SpritePoint(x2, y), SpritePoint(x2 - 1, y + 1),
            )
        }
    }

    /** Sprite-relative mouth pixels. */
    fun mouth(c: CompanionCharacter, mood: CompanionMood): List<SpritePoint> {
        val m = c.mouthRow
        return when (mood) {
            CompanionMood.GREAT, CompanionMood.SAVED ->
                listOf(SpritePoint(7, m + 1), SpritePoint(8, m + 1), SpritePoint(6, m), SpritePoint(9, m))
            CompanionMood.NORMAL, CompanionMood.MEASURING -> listOf(SpritePoint(7, m), SpritePoint(8, m))
            CompanionMood.TIRED -> listOf(SpritePoint(8, m))
        }
    }

    fun accessories(c: CompanionCharacter, a: Accessories, bob: Int, droop: Int, out: MutableList<StagePixel>) {
        val ox = CompanionStage.SPRITE_X
        val hy = CompanionStage.SPRITE_Y + c.hatAnchorY + bob
        if (a.hat) {
            out += StagePixel(ox + 7, hy - 2, CompanionStage.PINK)
            out += StagePixel(ox + 8, hy - 2, CompanionStage.YELLOW)
            for (i in 6..9) {
                out += StagePixel(ox + i, hy - 1, CompanionStage.PINK)
                out += StagePixel(ox + i, hy, if (i % 2 == 1) CompanionStage.YELLOW else CompanionStage.PINK)
            }
        }
        if (a.orange) {
            // Round, left-right symmetric orange (drawn after the sprite and hat): leaf, top, wide middle, shaded bottom.
            out += StagePixel(ox + 7, hy - 2, CompanionStage.LEAF)
            out += StagePixel(ox + 8, hy - 2, CompanionStage.LEAF_DARK)
            out += StagePixel(ox + 7, hy - 1, CompanionStage.ORANGE)
            out += StagePixel(ox + 8, hy - 1, CompanionStage.ORANGE)
            for (i in 6..9) out += StagePixel(ox + i, hy, CompanionStage.ORANGE)
            out += StagePixel(ox + 7, hy, CompanionStage.ORANGE_HIGHLIGHT)
            out += StagePixel(ox + 7, hy + 1, CompanionStage.ORANGE_SHADE)
            out += StagePixel(ox + 8, hy + 1, CompanionStage.ORANGE_SHADE)
        }
        if (a.sunglasses) {
            // Sunglasses sit on the eyes, so they follow Tulip's droop with the rest of the face.
            val yy = CompanionStage.SPRITE_Y + c.eyeY + bob + droop
            val x = c.eyeX
            val x2 = c.rightEyeX
            for (row in yy..yy + 1) {
                for (dx in -1..1) {
                    out += StagePixel(ox + x + dx, row, CompanionStage.EYE)
                    out += StagePixel(ox + x2 + dx, row, CompanionStage.EYE)
                }
            }
            for (bx in x + 2..x2 - 2) out += StagePixel(ox + bx, yy, CompanionStage.EYE)
            out += StagePixel(ox + x - 1, yy, CompanionStage.WHITE)
            out += StagePixel(ox + x2 - 1, yy, CompanionStage.WHITE)
        }
        if (a.bow) {
            val bx = ox + c.bowAnchor.x
            val by = CompanionStage.SPRITE_Y + c.bowAnchor.y + bob
            out += StagePixel(bx, by, CompanionStage.PINK)
            out += StagePixel(bx + 2, by, CompanionStage.PINK)
            out += StagePixel(bx, by + 2, CompanionStage.PINK)
            out += StagePixel(bx + 2, by + 2, CompanionStage.PINK)
            out += StagePixel(bx + 1, by + 1, CompanionStage.BOW_KNOT)
        }
    }

    fun pattern(rows: List<String>, left: Int, top: Int, color: Int, out: MutableList<StagePixel>) {
        rows.forEachIndexed { dy, line ->
            line.forEachIndexed { dx, ch -> if (ch == 'X') out += StagePixel(left + dx, top + dy, color) }
        }
    }

    /** Sprite only, open eyes (picker thumbnails): 16x16 pixels at sprite coordinates. */
    fun thumbnail(c: CompanionCharacter): List<StagePixel> {
        val out = ArrayList<StagePixel>(256)
        c.rows.forEachIndexed { row, line ->
            line.forEachIndexed { col, ch -> c.palette[ch]?.let { out += StagePixel(col, row, it) } }
        }
        c.cheeks.forEach { out += StagePixel(it.x, it.y, CompanionStage.CHEEK) }
        c.nose.forEach { out += StagePixel(it.x, it.y, CompanionStage.EYE) }
        eyes(c, EyeState.OPEN).forEach { out += StagePixel(it.x, it.y, CompanionStage.EYE) }
        mouth(c, CompanionMood.NORMAL).forEach { out += StagePixel(it.x, it.y, CompanionStage.EYE) }
        return out
    }
}

/** Scrolling pixel ECG on a 36x10 grid. */
object PixelEcg {
    const val COLUMNS = 36
    const val ROWS = 10
    val PATTERN = intArrayOf(5, 5, 5, 5, 5, 5, 5, 4, 3, 4, 5, 5, 6, 8, 1, 5, 5, 5)

    fun y(column: Int, t: Int): Int = PATTERN[(column + t).mod(PATTERN.size)]

    /** Lit cells (x, y), one pixel per column with vertical gaps to the previous column filled. */
    fun pixels(t: Int): List<SpritePoint> {
        val out = ArrayList<SpritePoint>(COLUMNS * 3)
        var prev = -1
        for (x in 0 until COLUMNS) {
            val y = y(x, t)
            if (prev < 0) {
                out += SpritePoint(x, y)
            } else {
                for (yy in minOf(prev, y)..maxOf(prev, y)) out += SpritePoint(x, yy)
            }
            prev = y
        }
        return out
    }
}

/** Everything the user can choose in Settings > Companion. */
data class CompanionSettings(
    val character: CompanionCharacter = CompanionCharacter.PIP,
    val level: AnimationLevel = AnimationLevel.PLAYFUL,
    val accessories: Accessories = Accessories.NONE,
    val speechBubbles: Boolean = true,
    /** Raw name as typed; blank means "use the character's name". */
    val name: String = "",
) {
    val displayName: String get() = name.trim().ifEmpty { character.displayName }
}
