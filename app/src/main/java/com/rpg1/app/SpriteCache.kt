package com.rpg1.app

import android.graphics.Bitmap
import com.rpg1.core.CharClass
import com.rpg1.core.MonsterType
import com.rpg1.core.Race
import com.rpg1.core.art.Sprite
import com.rpg1.core.art.Sprites
import com.rpg1.render.HeroArt

/** Bitmaps of the text sprites for the Canvas UI (portraits, turn order, fallback map). */
object SpriteCache {
    private val cache = HashMap<String, Bitmap>()

    private fun bitmap(key: String, make: () -> IntArray): Bitmap =
        cache.getOrPut(key) { Bitmap.createBitmap(make(), 16, 16, Bitmap.Config.ARGB_8888) }

    fun get(sprite: Sprite) = bitmap(sprite.name) { sprite.pixels() }

    fun hero(race: Race, cls: CharClass): Bitmap = bitmap("hero_${cls.name}_${race.name}") { HeroArt.pixels(race, cls) }

    fun heroScale(race: Race) = HeroArt.scale(race)

    fun monster(type: MonsterType): Bitmap = get(
        when (type) {
            MonsterType.GIANT_RAT -> Sprites.GIANT_RAT
            MonsterType.KOBOLD -> Sprites.KOBOLD
            MonsterType.GOBLIN -> Sprites.GOBLIN
            MonsterType.SKELETON -> Sprites.SKELETON
            MonsterType.ZOMBIE -> Sprites.ZOMBIE
            MonsterType.ORC -> Sprites.ORC
            MonsterType.GNOLL -> Sprites.GNOLL
            MonsterType.GHOUL -> Sprites.GHOUL
            MonsterType.BUGBEAR -> Sprites.BUGBEAR
            MonsterType.OGRE -> Sprites.OGRE
        },
    )
}
