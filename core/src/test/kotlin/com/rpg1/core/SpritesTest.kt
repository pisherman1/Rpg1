package com.rpg1.core

import com.rpg1.core.art.Sprites
import kotlin.test.Test
import kotlin.test.assertEquals

class SpritesTest {
    @Test
    fun everySpriteIs16x16WithKnownColours() {
        for (sprite in Sprites.all + Sprites.overlays) {
            assertEquals(16, sprite.height, "${sprite.name} height")
            sprite.rows.forEachIndexed { i, row -> assertEquals(16, row.length, "${sprite.name} row $i") }
            sprite.pixels() // throws on unknown palette keys
        }
    }
}
