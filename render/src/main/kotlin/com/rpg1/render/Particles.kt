package com.rpg1.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A small CPU particle system; positions in world pixels, drawn additively. */
class Particles(private val rng: Random = Random(7)) {
    private class P(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float,
        val r: Float, val g: Float, val b: Float,
        val size: Float, val gravity: Float, val drag: Float,
    )

    private val list = ArrayList<P>()
    val count get() = list.size

    fun clear() = list.clear()

    private fun rnd(a: Float, b: Float) = a + rng.nextFloat() * (b - a)

    private fun add(
        x: Float, y: Float, vx: Float, vy: Float, life: Float, color: Int,
        size: Float = 1f, gravity: Float = 0f, drag: Float = 1.5f,
    ) {
        if (list.size >= MAX) return
        list += P(x, y, vx, vy, life, life,
            ((color shr 16) and 255) / 255f, ((color shr 8) and 255) / 255f, (color and 255) / 255f,
            size, gravity, drag)
    }

    /** Radial spray of sparks. */
    fun burst(x: Float, y: Float, color: Int, n: Int, speed: Float = 60f, gravity: Float = 80f, life: Float = 0.4f) {
        repeat(n) {
            val a = rnd(0f, 6.2832f)
            val s = rnd(speed * 0.4f, speed)
            add(x, y, cos(a) * s, sin(a) * s - speed * 0.3f, rnd(life * 0.5f, life), color, gravity = gravity)
        }
    }

    /** Slowly rising motes (healing, holy light, level up). */
    fun motes(x: Float, y: Float, color: Int, n: Int, spread: Float = 6f) {
        repeat(n) {
            add(x + rnd(-spread, spread), y + rnd(-spread, spread), rnd(-4f, 4f), rnd(-30f, -12f),
                rnd(0.6f, 1.1f), color, gravity = -6f, drag = 0.5f)
        }
    }

    fun ember(x: Float, y: Float) {
        add(x + rnd(-1.5f, 1.5f), y, rnd(-8f, 8f), rnd(-35f, -15f), rnd(0.35f, 0.8f),
            if (rng.nextBoolean()) 0xFFB040 else 0xFF6A20, gravity = -10f, drag = 1f)
    }

    /** Grey puff when something dies. */
    fun dust(x: Float, y: Float, n: Int) {
        repeat(n) {
            val a = rnd(0f, 6.2832f)
            val s = rnd(8f, 28f)
            add(x + rnd(-4f, 4f), y + rnd(-2f, 4f), cos(a) * s, sin(a) * s * 0.5f - 8f, rnd(0.5f, 0.9f),
                0x6A6278, size = 2f, gravity = -8f, drag = 2f)
        }
    }

    fun update(dt: Float) {
        val it = list.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove()
                continue
            }
            val damp = 1f / (1f + p.drag * dt)
            p.vx *= damp
            p.vy = p.vy * damp + p.gravity * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
        }
    }

    /** Appends one emissive sprite per particle. */
    fun emit(out: FloatList, pixelId: Int) {
        for (p in list) {
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            val s = p.size
            out.add(pixelId.toFloat()); out.add(p.x - s / 2); out.add(p.y - s / 2); out.add(s); out.add(s)
            out.add(p.r); out.add(p.g); out.add(p.b); out.add(a); out.add(0f); out.add(0f)
        }
    }

    companion object {
        const val MAX = 800
    }
}
