package com.example.healthcheck.core.companion

import com.example.healthcheck.core.scoring.StrainLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSpriteTest {
    @Test fun sevenCharactersInPickerOrder() {
        assertEquals(
            listOf("Pip", "Puff", "Twinkle", "Biscuit", "Capy", "Tulip", "Comet"),
            CompanionCharacter.pickerOrder.map { it.displayName },
        )
        assertEquals(CompanionCharacter.entries.toSet(), CompanionCharacter.pickerOrder.toSet())
    }

    @Test fun everySpriteHas16RowsOfExactly8Characters() {
        CompanionCharacter.entries.forEach { c ->
            assertEquals("${c.displayName} row count", 16, c.halfRows.size)
            c.halfRows.forEachIndexed { i, row ->
                assertEquals("${c.displayName} row $i \"$row\"", 8, row.length)
            }
        }
    }

    @Test fun mirroredSpritesAre16ColumnsAndSymmetric() {
        CompanionCharacter.entries.forEach { c ->
            assertEquals(16, c.rows.size)
            c.rows.forEachIndexed { i, row ->
                assertEquals("${c.displayName} row $i", 16, row.length)
                assertEquals(row.reversed(), row)
                assertEquals(c.halfRows[i], row.take(8))
            }
        }
    }

    @Test fun everySpriteCharacterHasAPaletteColour() {
        CompanionCharacter.entries.forEach { c ->
            val used = c.halfRows.joinToString("").toSet() - '.'
            assertTrue("${c.displayName} missing ${used - c.palette.keys}", c.palette.keys.containsAll(used))
        }
    }

    @Test fun capyRow10WasFixed() {
        assertEquals(".obbblll", CompanionCharacter.CAPY.halfRows[10])
    }

    @Test fun rightEyeIsMirrored() {
        CompanionCharacter.entries.forEach { assertEquals(15 - it.eyeX, it.rightEyeX) }
    }
}

class CompanionBoundsTest {
    private val allAccessories = Accessories(orange = true, hat = true, sunglasses = true, bow = true)

    /** Pixels outside the stage for every reachable tick of every mood and level. */
    private fun outside(c: CompanionCharacter, acc: Accessories): Set<Triple<CompanionMood, AnimationLevel, StagePixel>> {
        val out = HashSet<Triple<CompanionMood, AnimationLevel, StagePixel>>()
        for (mood in CompanionMood.entries) for (level in AnimationLevel.entries) {
            // 2520 = lcm of every period used (4, 8, 14, 20, 30, 36).
            for (t in 0 until 2520) {
                CompanionRenderer.frame(FrameSpec(c, mood, level, acc, t)).forEach { p ->
                    if (!CompanionStage.inside(p.x, p.y)) out += Triple(mood, level, p)
                }
            }
        }
        return out
    }

    @Test fun faceAndBodyStayInsideTheStageForAllCharacters() {
        CompanionCharacter.entries.forEach { c ->
            assertEquals("${c.displayName}", emptySet<Any>(), outside(c, Accessories.NONE))
        }
    }

    @Test fun accessoriesStayInsideTheStageAtRest() {
        CompanionCharacter.entries.forEach { c ->
            Accessories.ALL_SINGLE.forEach { (name, acc) ->
                for (mood in CompanionMood.entries) {
                    val px = CompanionRenderer.frame(FrameSpec(c, mood, AnimationLevel.OFF, acc, 0))
                    val bad = px.filterNot { CompanionStage.inside(it.x, it.y) }
                    assertTrue("${c.displayName} $name $mood: $bad", bad.isEmpty())
                }
            }
        }
    }

    @Test fun accessoriesStayInsideTheStageWhileAnimating() {
        CompanionCharacter.entries.forEach { c ->
            assertEquals("${c.displayName}", emptySet<Any>(), outside(c, allAccessories))
            Accessories.ALL_SINGLE.forEach { (name, acc) ->
                assertEquals("${c.displayName} $name", emptySet<Any>(), outside(c, acc))
            }
        }
    }

    @Test fun measurementHeartAndEcgFitTheirGrids() {
        val heart = CompanionRenderer.frame(FrameSpec(CompanionCharacter.PIP, CompanionMood.MEASURING, AnimationLevel.PLAYFUL))
        assertTrue(heart.all { CompanionStage.inside(it.x, it.y) })
        for (t in 0 until 36) {
            PixelEcg.pixels(t).forEach { assertTrue(it.x in 0 until PixelEcg.COLUMNS && it.y in 0 until PixelEcg.ROWS) }
        }
    }
}

class CompanionOrangeTest {
    private fun orange(c: CompanionCharacter, bob: Int): List<StagePixel> =
        ArrayList<StagePixel>().also { CompanionRenderer.accessories(c, Accessories(orange = true), bob, 0, it) }

    private fun hy(c: CompanionCharacter, bob: Int) = CompanionStage.SPRITE_Y + c.hatAnchorY + bob

    @Test fun orangeIsSymmetricAroundTheSpriteCentreExceptTheHighlight() {
        // Sprite columns 0..15 have their centre at 7.5, i.e. stage x 11.5: x mirrors to 23 - x.
        val mirror = 2 * CompanionStage.SPRITE_X + 15
        CompanionCharacter.entries.forEach { c ->
            for (bob in -3..1) {
                val px = orange(c, bob)
                val highlight = px.single { it.color == CompanionStage.ORANGE_HIGHLIGHT }
                assertEquals(StagePixel(CompanionStage.SPRITE_X + 7, hy(c, bob), CompanionStage.ORANGE_HIGHLIGHT), highlight)
                val rest = px - highlight
                val cells = rest.map { it.x to it.y }.toSet()
                assertEquals("${c.displayName} has duplicate cells", rest.size, cells.size)
                assertEquals("${c.displayName} shape", cells, cells.map { (x, y) -> (mirror - x) to y }.toSet())
                // Fruit colours are mirrored too (shading is top-to-bottom only); the leaf is two-tone by design.
                val fruit = rest.filter { it.y != hy(c, bob) - 2 }.associate { (it.x to it.y) to it.color }
                fruit.forEach { (cell, color) ->
                    assertEquals("${c.displayName} $cell", color, fruit.getValue((mirror - cell.first) to cell.second))
                }
            }
        }
    }

    @Test fun orangeShapeAndTopRow() {
        CompanionCharacter.entries.forEach { c ->
            for (bob in -3..1) {
                val px = orange(c, bob)
                val h = hy(c, bob)
                assertEquals("${c.displayName} top row", h - 2, px.minOf { it.y })
                assertEquals(h + 1, px.maxOf { it.y })
                val byRow = px.filter { it.color != CompanionStage.ORANGE_HIGHLIGHT }.groupBy({ it.y }, { it.x - CompanionStage.SPRITE_X })
                assertEquals(listOf(7, 8), byRow.getValue(h - 2).sorted())
                assertEquals(listOf(7, 8), byRow.getValue(h - 1).sorted())
                assertEquals(listOf(6, 7, 8, 9), byRow.getValue(h).sorted())
                assertEquals(listOf(7, 8), byRow.getValue(h + 1).sorted())
            }
        }
    }

    @Test fun orangeDrawsOnTopOfTheSpriteAndHat() {
        CompanionCharacter.entries.forEach { c ->
            val frame = CompanionRenderer.frame(
                FrameSpec(c, CompanionMood.NORMAL, AnimationLevel.OFF, Accessories(orange = true, hat = true)),
            )
            // The orange's own last pixel per cell (the highlight wins over the fruit underneath it).
            val expected = orange(c, 0).associate { (it.x to it.y) to it.color }
            expected.forEach { (cell, color) ->
                val topmost = frame.last { it.x == cell.first && it.y == cell.second }
                assertEquals("${c.displayName} $cell", color, topmost.color)
            }
        }
    }
}

class CompanionHeartHugTest {
    private val hugMoods = listOf(CompanionMood.GREAT, CompanionMood.SAVED)

    private fun mouthOnStage(c: CompanionCharacter, bob: Int) =
        CompanionRenderer.mouth(c, CompanionMood.GREAT).map { (CompanionStage.SPRITE_X + it.x) to (CompanionStage.SPRITE_Y + it.y + bob) }.toSet()

    @Test fun noHeartPixelOverlapsAMouthPixel() {
        CompanionCharacter.entries.forEach { c ->
            assertEquals(CompanionRenderer.mouth(c, CompanionMood.GREAT), CompanionRenderer.mouth(c, CompanionMood.SAVED))
            for (bob in -3..0) for (beat in listOf(false, true)) {
                val heart = CompanionRenderer.heartHug(c, bob, beat).map { it.x to it.y }.toSet()
                val overlap = heart intersect mouthOnStage(c, bob)
                assertTrue("${c.displayName} bob=$bob beat=$beat overlaps $overlap", overlap.isEmpty())
            }
        }
    }

    @Test fun smileIsTheTopmostPixelInEveryHugFrame() {
        val all = Accessories(orange = true, hat = true, sunglasses = true, bow = true)
        CompanionCharacter.entries.forEach { c ->
            for (mood in hugMoods) for (level in AnimationLevel.entries) for (acc in listOf(Accessories.NONE, all)) {
                for (t in 0 until 56) {
                    val frame = CompanionRenderer.frame(FrameSpec(c, mood, level, acc, t))
                    val bob = CompanionMotion.bob(mood, level, t)
                    mouthOnStage(c, bob).forEach { (x, y) ->
                        val top = frame.last { it.x == x && it.y == y }
                        assertEquals("${c.displayName} $mood $level t=$t mouth ($x,$y) hidden", CompanionStage.EYE, top.color)
                    }
                }
            }
        }
    }

    @Test fun heartIsCentredInsideTheStageAndKeepsTheBeat() {
        CompanionCharacter.entries.forEach { c ->
            val rest = CompanionRenderer.heartHug(c, 0, beat = false)
            val beat = CompanionRenderer.heartHug(c, 0, beat = true)
            val xs = rest.map { it.x }
            // Same centring as the spec's 7x6 heart at x 9..15 (centre 12).
            assertEquals("${c.displayName}", 12.0, (xs.min() + xs.max()) / 2.0, 0.0)
            assertEquals(if (c.heart.small) 5 else 7, xs.max() - xs.min() + 1)
            assertEquals(rest.map { it.x to it.y - 1 }, beat.map { it.x to it.y })
            assertTrue(rest.dropLast(1).all { it.color == CompanionStage.PINK })
            assertTrue(beat.dropLast(1).all { it.color == CompanionStage.RED })
            assertEquals(CompanionStage.HEART_HIGHLIGHT, rest.last().color)
            for (bob in -3..0) for (b in listOf(false, true)) {
                assertTrue(CompanionRenderer.heartHug(c, bob, b).all { CompanionStage.inside(it.x, it.y) })
            }
        }
    }
}

class CompanionMotionTest {
    @Test fun moodFollowsTheScoreLabel() {
        assertEquals(CompanionMood.GREAT, CompanionMotion.moodFor(StrainLabel.LOW))
        assertEquals(CompanionMood.NORMAL, CompanionMotion.moodFor(StrainLabel.NORMAL))
        assertEquals(CompanionMood.TIRED, CompanionMotion.moodFor(StrainLabel.ELEVATED))
        assertEquals(CompanionMood.NORMAL, CompanionMotion.moodFor(null))
        assertEquals(CompanionMood.GREAT, CompanionMotion.moodFor(StrainLabel.forScore(80)))
        assertEquals(CompanionMood.NORMAL, CompanionMotion.moodFor(StrainLabel.forScore(60)))
        assertEquals(CompanionMood.TIRED, CompanionMotion.moodFor(StrainLabel.forScore(30)))
    }

    private fun bobs(mood: CompanionMood, level: AnimationLevel, n: Int) = (0 until n).map { CompanionMotion.bob(mood, level, it) }

    @Test fun greatAndSavedBob() {
        val playful = listOf(0, -1, -2, -3, -3, -2, -1, 0, 0, 0, 0, 0, 0, 0)
        val calm = listOf(0, 0, -1, -1, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0)
        for (m in listOf(CompanionMood.GREAT, CompanionMood.SAVED)) {
            assertEquals(playful, bobs(m, AnimationLevel.PLAYFUL, 14))
            assertEquals(calm, bobs(m, AnimationLevel.CALM, 14))
            assertEquals(playful, (14 until 28).map { CompanionMotion.bob(m, AnimationLevel.PLAYFUL, it) })
        }
    }

    @Test fun normalBob() {
        assertEquals(List(10) { 0 } + List(10) { -1 }, bobs(CompanionMood.NORMAL, AnimationLevel.PLAYFUL, 20))
        assertTrue(bobs(CompanionMood.NORMAL, AnimationLevel.CALM, 40).all { it == 0 })
    }

    @Test fun tiredAndMeasuringBob() {
        for (level in listOf(AnimationLevel.PLAYFUL, AnimationLevel.CALM)) {
            assertEquals(List(15) { 0 } + List(15) { 1 }, bobs(CompanionMood.TIRED, level, 30))
            assertEquals(listOf(-1, -1, 0, 0, 0, 0, 0, 0), bobs(CompanionMood.MEASURING, level, 8))
        }
    }

    @Test fun offNeverBobs() {
        CompanionMood.entries.forEach { m -> assertTrue(bobs(m, AnimationLevel.OFF, 100).all { it == 0 }) }
    }

    @Test fun eyesAndBlink() {
        assertEquals(EyeState.HAPPY, CompanionMotion.eyeState(CompanionMood.GREAT, 35))
        assertEquals(EyeState.HAPPY, CompanionMotion.eyeState(CompanionMood.SAVED, 0))
        assertEquals(EyeState.CLOSED, CompanionMotion.eyeState(CompanionMood.TIRED, 0))
        val normal = (0 until 72).map { CompanionMotion.eyeState(CompanionMood.NORMAL, it) }
        assertEquals(4, normal.count { it == EyeState.CLOSED })
        assertEquals(EyeState.OPEN, CompanionMotion.eyeState(CompanionMood.MEASURING, 0))
    }

    @Test fun faceCoordinatesMatchTheSpec() {
        val pip = CompanionCharacter.PIP
        assertEquals(
            listOf(SpritePoint(4, 8), SpritePoint(4, 9), SpritePoint(11, 8), SpritePoint(11, 9)),
            CompanionRenderer.eyes(pip, EyeState.OPEN),
        )
        assertEquals(
            listOf(SpritePoint(3, 9), SpritePoint(4, 9), SpritePoint(11, 9), SpritePoint(12, 9)),
            CompanionRenderer.eyes(pip, EyeState.CLOSED),
        )
        assertEquals(
            listOf(SpritePoint(3, 9), SpritePoint(4, 8), SpritePoint(5, 9), SpritePoint(12, 9), SpritePoint(11, 8), SpritePoint(10, 9)),
            CompanionRenderer.eyes(pip, EyeState.HAPPY),
        )
        assertEquals(listOf(SpritePoint(8, 10)), CompanionRenderer.mouth(pip, CompanionMood.TIRED))
        assertEquals(listOf(SpritePoint(7, 10), SpritePoint(8, 10)), CompanionRenderer.mouth(pip, CompanionMood.MEASURING))
    }

    @Test fun tulipDroopsWhenTired() {
        val tulip = CompanionCharacter.TULIP
        val tired = CompanionRenderer.frame(FrameSpec(tulip, CompanionMood.TIRED, AnimationLevel.OFF))
        // Row 0's stalk tip moves from stage y 5 to 6; closed eyes move from row y+1 = 5 to 6 (+5 stage offset).
        assertFalse(tired.any { it.y == 5 && it.x == 11 && it.color == tulip.palette['o'] })
        assertTrue(tired.any { it.y == 6 && it.x == 11 && it.color == tulip.palette['o'] })
        assertTrue(tired.any { it.x == 4 + 4 && it.y == 5 + 5 + 1 && it.color == CompanionStage.EYE })
    }

    @Test fun texts() {
        assertEquals("Squeak! Great recovery today!", CompanionText.bubble(CompanionMood.GREAT, "Squeak!"))
        assertEquals("Woof! Saved! Nice work.", CompanionText.bubble(CompanionMood.SAVED, "Woof!"))
        assertEquals("Building your baseline, 2 of 5 days", CompanionText.buildingBaseline(2, 5))
        assertEquals(12, CompanionText.cleanName("A very long buddy name").length)
        assertEquals("Capy", CompanionSettings(character = CompanionCharacter.CAPY, name = "  ").displayName)
        assertEquals("Bun", CompanionSettings(name = "Bun").displayName)
    }
}

class CompanionParticlesTest {
    private fun run(mood: CompanionMood, level: AnimationLevel, ticks: Int): CompanionEngine =
        CompanionEngine(1).apply { repeat(ticks) { step(mood, level) } }

    @Test fun greatSpawnsEvery5TicksAndHalfInCalm() {
        assertEquals(1, run(CompanionMood.GREAT, AnimationLevel.PLAYFUL, 5).particles.size)
        assertEquals(2, run(CompanionMood.GREAT, AnimationLevel.PLAYFUL, 10).particles.size)
        assertEquals(1, run(CompanionMood.GREAT, AnimationLevel.CALM, 10).particles.size)
        // Life 14: never more than 3 alive at once.
        assertTrue(run(CompanionMood.GREAT, AnimationLevel.PLAYFUL, 200).particles.size <= 3)
        run(CompanionMood.GREAT, AnimationLevel.PLAYFUL, 100).particles.forEach {
            assertTrue(it.kind == ParticleKind.HEART || it.kind == ParticleKind.SPARKLE)
        }
    }

    @Test fun offHasNoParticles() {
        CompanionMood.entries.forEach { assertTrue(run(it, AnimationLevel.OFF, 100).particles.isEmpty()) }
    }

    @Test fun savedBurstsConfettiOnceOnEntering() {
        val e = CompanionEngine(3)
        e.step(CompanionMood.SAVED, AnimationLevel.PLAYFUL)
        assertEquals(28, e.particles.size)
        assertTrue(e.particles.all { it.kind == ParticleKind.CONFETTI && it.vy in -2.8f..-1f && it.vx in -0.8f..0.8f })
        repeat(30) { e.step(CompanionMood.SAVED, AnimationLevel.PLAYFUL) }
        assertTrue("confetti dies after 24 ticks and doesn't repeat", e.particles.isEmpty())
        assertEquals(14, run(CompanionMood.SAVED, AnimationLevel.CALM, 1).particles.size)
    }

    @Test fun tiredZs() {
        val e = run(CompanionMood.TIRED, AnimationLevel.PLAYFUL, 22)
        assertEquals(ParticleKind.Z, e.particles.single().kind)
        assertEquals(9, e.pixels().size) // 3x5 "Z" glyph has 9 lit pixels
    }

    @Test fun ecgPatternScrollsAndGapsAreFilled() {
        assertEquals(PixelEcg.y(1, 0), PixelEcg.y(0, 1))
        assertEquals(PixelEcg.y(0, 0), PixelEcg.y(18, 0))
        for (t in 0 until 18) {
            val px = PixelEcg.pixels(t)
            for (x in 1 until PixelEcg.COLUMNS) {
                val col = px.filter { it.x == x }.map { it.y }.sorted()
                assertEquals((col.first()..col.last()).toList(), col)
                val a = PixelEcg.y(x - 1, t)
                val b = PixelEcg.y(x, t)
                assertEquals(minOf(a, b), col.first())
                assertEquals(maxOf(a, b), col.last())
            }
        }
    }
}
