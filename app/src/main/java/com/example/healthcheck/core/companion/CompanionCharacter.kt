package com.example.healthcheck.core.companion

/** A sprite-relative pixel position (0..15). */
data class SpritePoint(val x: Int, val y: Int)

/**
 * Where the heart hug sits: [top] is its sprite-relative top row (stage y = 5 + top + bob) and [small]
 * picks the 5x4 heart instead of the 7x6 one. Chosen per character so the smile is never covered,
 * even when the heart lifts 1 pixel on the beat.
 */
data class HeartHug(val top: Int, val small: Boolean)

/**
 * The 7 pixel companions. Every row below is the LEFT 8 pixels; the right half is the mirror image.
 * Colours are packed 0xAARRGGBB. All original designs.
 */
enum class CompanionCharacter(
    val displayName: String,
    val species: String,
    val palette: Map<Char, Int>,
    /** 16 rows of exactly 8 characters ('.' = transparent). */
    val halfRows: List<String>,
    val eyeX: Int,
    val eyeY: Int,
    val mouthRow: Int,
    val nose: List<SpritePoint>,
    /** Left and right cheek. */
    val cheeks: List<SpritePoint>,
    val hatAnchorY: Int,
    val bowAnchor: SpritePoint,
    val voice: String,
    val heart: HeartHug,
    /** Tulip droops (rows 0-8 and the face move down 1 pixel) when tired. */
    val droopsWhenTired: Boolean = false,
    /** Comet's antenna star ('a' pixels) twinkles. */
    val twinklingAntenna: Boolean = false,
) {
    PIP(
        "Pip", "guinea pig",
        mapOf('o' to 0xFF3A2A22.toInt(), 'b' to 0xFFC98B4F.toInt(), 'l' to 0xFFFFF3E0.toInt(), 'p' to 0xFFF4A6A0.toInt()),
        listOf(
            "........", "........", "...oo...", "..oppo..", "..oppooo", ".oobbbbb", ".obbbbbb", ".obbbbbl",
            ".obbbbll", ".obbbbll", ".obbbbbl", ".obbbbbb", "..obbbbb", "..obbbbb", "..oo....", "........",
        ),
        eyeX = 4, eyeY = 8, mouthRow = 10,
        nose = listOf(SpritePoint(7, 9), SpritePoint(8, 9)),
        cheeks = listOf(SpritePoint(3, 10), SpritePoint(12, 10)),
        hatAnchorY = 3, bowAnchor = SpritePoint(2, 2), voice = "Squeak!",
        heart = HeartHug(top = 13, small = true),
    ),
    PUFF(
        "Puff", "soft robot",
        mapOf('o' to 0xFF5A6B7B.toInt(), 'b' to 0xFFFFFFFF.toInt(), 'a' to 0xFFFF6B81.toInt()),
        listOf(
            ".......o", "......oa", ".......o", "....oooo", "...obbbb", "..obbbbb", "..obbbbb", "..obbbbb",
            "..obbbbb", ".oobbbbb", ".obbbbab", ".oobbaaa", "..obbbaa", "...obbba", "...ooooo", "....oo..",
        ),
        eyeX = 5, eyeY = 6, mouthRow = 8,
        nose = emptyList(),
        cheeks = listOf(SpritePoint(4, 8), SpritePoint(11, 8)),
        hatAnchorY = 2, bowAnchor = SpritePoint(3, 3), voice = "Beep boop!",
        heart = HeartHug(top = 11, small = false),
    ),
    TWINKLE(
        "Twinkle", "star sprite",
        mapOf('o' to 0xFF9A6400.toInt(), 'b' to 0xFFFFD54A.toInt()),
        listOf(
            ".......o", "......ob", "......ob", ".....obb", "ooooobbb", "obbbbbbb", ".obbbbbb", "..obbbbb",
            "..obbbbb", "...obbbb", "..obbbbb", "..obbbbo", ".obbbbo.", ".obbbo..", ".oooo...", "........",
        ),
        eyeX = 5, eyeY = 6, mouthRow = 9,
        nose = emptyList(),
        cheeks = listOf(SpritePoint(4, 8), SpritePoint(11, 8)),
        // Hat anchor raised from the spec's -1 to 0 so the hat/orange stay on stage at the peak of the bounce.
        hatAnchorY = 0, bowAnchor = SpritePoint(2, 3), voice = "Twinkle!",
        heart = HeartHug(top = 12, small = true),
    ),
    BISCUIT(
        "Biscuit", "puppy",
        mapOf('o' to 0xFF4A3426.toInt(), 'b' to 0xFFF5DDB5.toInt(), 'e' to 0xFFA8744A.toInt(), 'l' to 0xFFFFF6E6.toInt()),
        listOf(
            "........", "...oo...", "..oeeo..", "..oeeooo", "..oeebbb", "..oeebbb", "...obbbb", "...obbbb",
            "...obbll", "...obbll", "...oobbl", "....obbb", "...obbbb", "...obbbb", "...oo.oo", "........",
        ),
        eyeX = 5, eyeY = 5, mouthRow = 9,
        nose = listOf(SpritePoint(7, 8), SpritePoint(8, 8)),
        cheeks = listOf(SpritePoint(4, 8), SpritePoint(11, 8)),
        hatAnchorY = 2, bowAnchor = SpritePoint(2, 1), voice = "Woof!",
        heart = HeartHug(top = 12, small = true),
    ),
    CAPY(
        "Capy", "capybara",
        mapOf('o' to 0xFF4A3426.toInt(), 'b' to 0xFFB8844F.toInt(), 'l' to 0xFFD9B38A.toInt(), 'n' to 0xFF5A3A28.toInt()),
        listOf(
            "........", "........", "...oo...", "..obbo..", "..obbooo", ".obbbbbb", ".obbbbbb", ".obbbbbb",
            // Row 10 was ".obbbblll" (9 chars) in the spec; fixed to ".obbblll" to close the 3-row muzzle.
            ".obbblll", ".obbblnn", ".obbblll", ".obbbbbb", "..obbbbb", "..obbbbb", "..oo....", "........",
        ),
        eyeX = 4, eyeY = 6, mouthRow = 11,
        nose = emptyList(),
        cheeks = listOf(SpritePoint(3, 9), SpritePoint(12, 9)),
        hatAnchorY = 3, bowAnchor = SpritePoint(2, 3), voice = "Prrp!",
        // The smile (rows 11-12) leaves no room below it, so the heart sits on the snout above the smile.
        heart = HeartHug(top = 8, small = true),
    ),
    TULIP(
        "Tulip", "tulip",
        mapOf('o' to 0xFF7A2E55.toInt(), 'b' to 0xFFFF8FB1.toInt(), 'g' to 0xFF5BBF6A.toInt()),
        listOf(
            ".......o", "..o..obb", "..obbobb", "..obbbbb", "..obbbbb", "..obbbbb", "..obbbbb", "...obbbb",
            "....obbb", ".....obb", "......og", "..oo..og", ".oggoogg", ".ogggogg", "....oooo", "........",
        ),
        eyeX = 4, eyeY = 4, mouthRow = 6,
        nose = emptyList(),
        cheeks = listOf(SpritePoint(3, 6), SpritePoint(12, 6)),
        hatAnchorY = 0, bowAnchor = SpritePoint(2, 4), voice = "La la!",
        heart = HeartHug(top = 9, small = false),
        droopsWhenTired = true,
    ),
    COMET(
        "Comet", "comet critter",
        mapOf('o' to 0xFF4B3A8C.toInt(), 'b' to 0xFFB9A3F5.toInt(), 'l' to 0xFFEFE7FF.toInt(), 'a' to 0xFFFFD54A.toInt()),
        listOf(
            ".......a", ".......o", ".......o", "....oooo", "...obbbb", "..obbbbb", "..obbbbb", "..obbbbb",
            "..obbbbb", "..obbbll", "..obblll", "..obblll", "...obbll", "...obbbb", "...ooooo", "....oo..",
        ),
        eyeX = 5, eyeY = 6, mouthRow = 8,
        nose = emptyList(),
        cheeks = listOf(SpritePoint(4, 8), SpritePoint(11, 8)),
        hatAnchorY = 2, bowAnchor = SpritePoint(3, 3), voice = "Bloop!",
        heart = HeartHug(top = 11, small = false),
        twinklingAntenna = true,
    );

    /** 16 rows of 16 characters: each half row followed by its mirror image. */
    val rows: List<String> by lazy { halfRows.map { it + it.reversed() } }

    /** Right eye x = 15 - left eye x. */
    val rightEyeX: Int get() = 15 - eyeX

    companion object {
        /** Picker order. */
        val pickerOrder: List<CompanionCharacter> = listOf(PIP, PUFF, TWINKLE, BISCUIT, CAPY, TULIP, COMET)
    }
}
