package com.example.healthcheck.core.companion

import kotlin.math.floor
import kotlin.random.Random

enum class ParticleKind { HEART, SPARKLE, Z, CONFETTI }

class Particle(
    val kind: ParticleKind,
    var x: Float,
    var y: Float,
    val vx: Float,
    var vy: Float,
    val gravity: Float,
    val life: Int,
    val color: Int,
) {
    var age: Int = 0
        internal set
}

/**
 * Stepped animation state: one [step] per 100 ms tick. Holds the tick counter and particles.
 * Not thread-safe; drive it from one coroutine.
 */
class CompanionEngine(seed: Long = 42L) {
    private val random = Random(seed)
    private val _particles = ArrayList<Particle>()
    val particles: List<Particle> get() = _particles

    var tick: Int = 0
        private set
    private var lastMood: CompanionMood? = null

    fun step(mood: CompanionMood, level: AnimationLevel) {
        tick++
        val t = tick
        // Advance existing particles.
        val it = _particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx
            p.y += p.vy
            p.vy += p.gravity
            p.age++
            if (p.age >= p.life) it.remove()
        }
        if (level == AnimationLevel.OFF) {
            _particles.clear()
            lastMood = mood
            return
        }
        val rate = if (level == AnimationLevel.CALM) 2 else 1
        when (mood) {
            CompanionMood.GREAT -> if (t % (5 * rate) == 0) {
                val heart = random.nextBoolean()
                _particles += Particle(
                    if (heart) ParticleKind.HEART else ParticleKind.SPARKLE,
                    x = random.nextInt(3, 21).toFloat(), y = (CompanionStage.SPRITE_Y + 9).toFloat(),
                    vx = 0f, vy = -0.7f, gravity = 0f, life = 14,
                    color = if (heart) CompanionStage.PINK else CompanionStage.YELLOW,
                )
            }
            CompanionMood.NORMAL -> if (t % (30 * rate) == 0) {
                _particles += Particle(
                    ParticleKind.SPARKLE,
                    x = random.nextInt(4, 19).toFloat(), y = (CompanionStage.SPRITE_Y + 8).toFloat(),
                    vx = 0f, vy = -0.3f, gravity = 0f, life = 10, color = CompanionStage.YELLOW,
                )
            }
            CompanionMood.TIRED -> if (t % (22 * rate) == 0) {
                _particles += Particle(
                    ParticleKind.Z,
                    x = (CompanionStage.SPRITE_X + 14).toFloat(), y = (CompanionStage.SPRITE_Y + 2).toFloat(),
                    vx = 0.3f, vy = -0.4f, gravity = 0f, life = 22, color = CompanionStage.Z_COLOR,
                )
            }
            CompanionMood.SAVED -> if (lastMood != CompanionMood.SAVED) burst(CONFETTI_COUNT / rate)
            CompanionMood.MEASURING -> Unit
        }
        lastMood = mood
    }

    private fun burst(count: Int) {
        repeat(count) {
            _particles += Particle(
                ParticleKind.CONFETTI,
                x = 12f + random.nextInt(-1, 2), y = 8f + random.nextInt(-1, 2),
                vx = random.nextDouble(-0.8, 0.8).toFloat(),
                vy = -random.nextDouble(1.0, 2.8).toFloat(),
                gravity = 0.25f, life = 24,
                color = CONFETTI_COLORS[random.nextInt(CONFETTI_COLORS.size)],
            )
        }
    }

    /** Pixels for all live particles (may fall outside the stage; the stage clips them). */
    fun pixels(): List<StagePixel> {
        val out = ArrayList<StagePixel>(_particles.size * 9)
        for (p in _particles) {
            val x = floor(p.x).toInt()
            val y = floor(p.y).toInt()
            when (p.kind) {
                ParticleKind.HEART -> CompanionRenderer.pattern(SMALL_HEART, x, y, p.color, out)
                ParticleKind.SPARKLE -> {
                    CompanionRenderer.pattern(PLUS, x, y, CompanionStage.YELLOW, out)
                    out += StagePixel(x + 1, y + 1, CompanionStage.PALE_YELLOW)
                }
                ParticleKind.Z -> CompanionRenderer.pattern(LETTER_Z, x, y, p.color, out)
                ParticleKind.CONFETTI -> out += StagePixel(x, y, p.color)
            }
        }
        return out
    }

    companion object {
        const val CONFETTI_COUNT = 28
        val CONFETTI_COLORS = intArrayOf(
            0xFFFF6B81.toInt(), 0xFFFFD54A.toInt(), 0xFF4ADE80.toInt(), 0xFF60A5FA.toInt(), 0xFFC084FC.toInt(),
        )
        val SMALL_HEART = listOf("X.X", "XXX", ".X.")
        val PLUS = listOf(".X.", "XXX", ".X.")
        val LETTER_Z = listOf("XXX", "..X", ".X.", "X..", "XXX")
    }
}
