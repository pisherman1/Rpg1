package com.rpg1.render

import com.rpg1.core.CharClass
import com.rpg1.core.Gender
import com.rpg1.core.MonsterType
import com.rpg1.core.Race
import com.rpg1.core.art.Palette
import com.rpg1.core.art.Sprites

/**
 * Pixel data for a hero: the class sprite, its male or female overlay, then race details
 * (dwarven beards or braids, elven ears).
 */
object HeroArt {
    fun pixels(race: Race, cls: CharClass, gender: Gender = Gender.MALE): IntArray {
        val px = when (cls) {
            CharClass.FIGHTER -> Sprites.FIGHTER
            CharClass.ROGUE -> Sprites.ROGUE
            CharClass.WIZARD -> Sprites.WIZARD
            CharClass.CLERIC -> Sprites.CLERIC
        }.pixels()
        val overlay = Sprites.genderOverlay(cls.name, gender == Gender.FEMALE)
        overlay.rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch -> if (ch != '.') px[y * 16 + x] = Palette.colors.getValue(ch) }
        }
        val skin = Palette.colors.getValue('s')
        val outline = Palette.colors.getValue('k')
        fun set(x: Int, y: Int, c: Int) { px[y * 16 + x] = c }
        when (race) {
            Race.DWARF -> {
                val beard = 0xFFB0602A.toInt()
                if (gender == Gender.MALE) {
                    set(5, 6, beard); set(10, 6, beard)
                    for (x in 5..10) set(x, 7, beard)
                    for (x in 6..9) set(x, 8, beard)
                    set(7, 9, beard); set(8, 9, beard)
                } else {
                    // Thick braids hanging past the shoulders.
                    for (y in 6..10) { set(4, y, beard); set(11, y, beard) }
                    set(3, 7, outline); set(12, 7, outline); set(3, 9, outline); set(12, 9, outline)
                    set(4, 11, 0xFFF2C641.toInt()); set(11, 11, 0xFFF2C641.toInt())
                }
            }
            Race.ELF -> {
                set(3, 4, outline); set(12, 4, outline)
                set(3, 5, skin); set(12, 5, skin)
                set(2, 4, outline); set(13, 4, outline)
            }
            else -> {}
        }
        return px
    }

    /** Halflings (and dwarves, a little) are drawn shorter. */
    fun scale(race: Race) = when (race) {
        Race.HALFLING -> 0.8f
        Race.DWARF -> 0.9f
        else -> 1f
    }
}

/** Every world sprite, packed into one texture with 1px extruded borders to avoid bleeding. */
class Atlas private constructor(
    val width: Int,
    val height: Int,
    /** ARGB pixels, row-major from the top. */
    val pixels: IntArray,
    private val regions: List<FloatArray>,
    private val ids: Map<String, Int>,
) {
    fun id(name: String): Int = ids[name] ?: error("No sprite '$name' in atlas")

    /** u0, v0, u1, v1 for a sprite id. */
    fun region(id: Int): FloatArray = regions[id]

    /** RGBA bytes for texture upload. */
    fun rgbaBytes(): ByteArray {
        val out = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            val c = pixels[i]
            out[i * 4] = (c shr 16).toByte()
            out[i * 4 + 1] = (c shr 8).toByte()
            out[i * 4 + 2] = c.toByte()
            out[i * 4 + 3] = (c ushr 24).toByte()
        }
        return out
    }

    companion object {
        private const val CELL = 18

        fun heroKey(race: Race, cls: CharClass, gender: Gender = Gender.MALE) = "hero_${cls.name}_${race.name}_${gender.name}"
        fun monsterKey(type: MonsterType) = type.name.lowercase()

        /** Sprites made in code rather than drawn by hand. */
        private fun generated(): List<Pair<String, IntArray>> {
            val list = mutableListOf<Pair<String, IntArray>>()
            // Soft drop shadow under creatures.
            list += "shadow" to IntArray(256) { i ->
                val x = i % 16 - 7.5f
                val y = i / 16 - 13.5f
                val d = (x * x) / 36f + (y * y) / 4f
                if (d <= 1f) ((0.45f * (1f - d * 0.6f)) * 255).toInt() shl 24 else 0
            }
            // Darkening strip on floor squares right under a wall.
            list += "wall_shadow" to IntArray(256) { i ->
                val y = i / 16
                if (y < 6) ((0.55f * (1f - y / 6f)) * 255).toInt() shl 24 else 0
            }
            // White pixel for particles and solid quads.
            list += "pixel" to IntArray(256) { 0xFFFFFFFF.toInt() }
            // Glowing orb for spell projectiles.
            list += "orb" to IntArray(256) { i ->
                val x = i % 16 - 7.5f
                val y = i / 16 - 7.5f
                val d = kotlin.math.sqrt(x * x + y * y)
                when {
                    d < 1.6f -> 0xFFFFFFFF.toInt()
                    d < 3.2f -> 0xC0FFFFFF.toInt()
                    d < 4.6f -> 0x50FFFFFF
                    else -> 0
                }
            }
            return list
        }

        fun build(): Atlas {
            val entries = mutableListOf<Pair<String, IntArray>>()
            for (sprite in Sprites.all) entries += sprite.name to sprite.pixels()
            for (race in Race.entries) for (cls in CharClass.entries) for (g in Gender.entries) {
                entries += heroKey(race, cls, g) to HeroArt.pixels(race, cls, g)
            }
            entries += generated()

            val cols = 16
            val rows = (entries.size + cols - 1) / cols
            val w = cols * CELL
            val h = Integer.highestOneBit(rows * CELL - 1) shl 1
            val px = IntArray(w * h)
            val regions = mutableListOf<FloatArray>()
            val ids = mutableMapOf<String, Int>()
            entries.forEachIndexed { index, (name, src) ->
                val ox = (index % cols) * CELL
                val oy = (index / cols) * CELL
                // Copy with a 1px border that repeats the edge pixels.
                for (y in -1..16) for (x in -1..16) {
                    val sx = x.coerceIn(0, 15)
                    val sy = y.coerceIn(0, 15)
                    px[(oy + 1 + y) * w + ox + 1 + x] = src[sy * 16 + sx]
                }
                val u0 = (ox + 1f) / w
                val v0 = (oy + 1f) / h
                val region = if (name == "pixel") {
                    // Sample the middle so filtering never touches neighbours.
                    floatArrayOf((ox + 8f) / w, (oy + 8f) / h, (ox + 9f) / w, (oy + 9f) / h)
                } else {
                    floatArrayOf(u0, v0, u0 + 16f / w, v0 + 16f / h)
                }
                ids[name] = regions.size
                regions += region
            }
            return Atlas(w, h, px, regions, ids)
        }
    }
}
