package com.rpg1.render

import com.rpg1.core.Bot
import com.rpg1.core.CharClass
import com.rpg1.core.Dice
import com.rpg1.core.Game
import com.rpg1.core.MonsterType
import com.rpg1.core.PartyBuilder
import com.rpg1.core.Race
import com.rpg1.core.art.Sprites
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderTest {
    private val atlas = Atlas.build()

    @Test
    fun atlasHoldsEverySpriteWithExtrudedBorders() {
        for (s in Sprites.all) atlas.id(s.name)
        for (r in Race.entries) for (c in CharClass.entries) atlas.id(Atlas.heroKey(r, c))
        for (m in MonsterType.entries) atlas.id(Atlas.monsterKey(m))
        assertEquals(Integer.bitCount(atlas.height), 1, "atlas height should be a power of two")
        // Each region spans exactly one 16px cell and its border repeats the edge pixel.
        val reg = atlas.region(atlas.id("wall"))
        assertEquals(16f / atlas.width, reg[2] - reg[0], 1e-6f)
        val x0 = (reg[0] * atlas.width).toInt()
        val y0 = (reg[1] * atlas.height).toInt()
        assertEquals(atlas.pixels[y0 * atlas.width + x0], atlas.pixels[(y0 - 1) * atlas.width + x0 - 1])
        assertEquals(atlas.pixels.size * 4, atlas.rgbaBytes().size)
    }

    @Test
    fun animatorBuildsValidFramesThroughWholeGames() {
        val regionCount = generateSequence(0) { it + 1 }.takeWhile {
            runCatching { atlas.region(it) }.isSuccess
        }.count()
        for (seed in 1..6) {
            val b = PartyBuilder(Dice(Random(seed)))
            val game = Game(b.build(), Dice(Random(seed)))
            val anim = WorldAnimator(atlas)
            anim.setViewport(1584, 1080, 6)
            var frames = 0
            var maxEmissive = 0
            repeat(3000) {
                Bot.play(game, 1, maxDepth = 4)
                anim.update(game, game.drainFx(), 1f / 60f)
                val f = anim.buildFrame(game)
                frames++
                maxEmissive = maxOf(maxEmissive, f.emissiveCount)
                assertEquals(f.spriteCount * WorldFrame.SPRITE_STRIDE, f.sprites.size)
                assertEquals(f.lightCount * WorldFrame.LIGHT_STRIDE, f.lights.size)
                assertEquals(game.dungeon.width * game.dungeon.height * 2, f.vis.size)
                for (i in 0 until f.spriteCount) {
                    val id = f.sprites[i * WorldFrame.SPRITE_STRIDE].toInt()
                    assertTrue(id in 0 until regionCount, "bad sprite id $id")
                }
                assertTrue(f.camLeft.isFinite() && f.camTop.isFinite())
            }
            assertTrue(maxEmissive > 0, "no particles or projectiles ever appeared (seed $seed)")
        }
    }

    @Test
    fun screenAndTileConversionsAgree() {
        val b = PartyBuilder(Dice(Random(3)))
        val game = Game(b.build(), Dice(Random(3)))
        val anim = WorldAnimator(atlas)
        anim.setViewport(1200, 900, 5)
        anim.update(game, emptyList(), 0.016f)
        val h = game.leader
        val sx = anim.toScreenX(h.x * 16f + 8f)
        val sy = anim.toScreenY(h.y * 16f + 8f)
        assertEquals(h.x, anim.screenToTileX(sx))
        assertEquals(h.y, anim.screenToTileY(sy))
        // The camera starts centred on the active hero.
        assertEquals(600f, sx, 1f)
        assertEquals(450f, sy, 1f)
    }
}
