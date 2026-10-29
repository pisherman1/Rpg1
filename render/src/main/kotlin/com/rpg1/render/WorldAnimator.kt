package com.rpg1.render

import com.rpg1.core.Creature
import com.rpg1.core.Fx
import com.rpg1.core.FxKind
import com.rpg1.core.Game
import com.rpg1.core.Hero
import com.rpg1.core.ItemKind
import com.rpg1.core.Monster
import com.rpg1.core.Phase
import com.rpg1.core.Point
import com.rpg1.core.Tile
import com.rpg1.core.audio.SoundId
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Turns discrete game state into smooth motion: creatures glide between squares, lunge when
 * attacking and flash when hit; spells fly as glowing projectiles with particles and light;
 * the camera follows the action and shakes on big hits. Pure Kotlin, runs on the UI thread.
 */
class WorldAnimator(private val atlas: Atlas) {
    /** Screen size of the map view and integer pixel scale. */
    var viewW = 1
        private set
    var viewH = 1
        private set
    var scale = 4
        private set

    private var camX = 0f
    private var camY = 0f
    private var shake = 0f
    private var shakeX = 0f
    private var shakeY = 0f
    private var time = 0f
    private val rng = Random(42)

    private var game: Game? = null
    private var depth = -1

    private class Anim(var x: Float, var y: Float) {
        var flash = 0f
        var flashDelay = 0f
        var lungeX = 0f
        var lungeY = 0f
        var lungeT = 0f
        var moving = false
    }

    private class Ghost(val sprite: Int, val x: Float, val y: Float, var t: Float = 0f)
    private class Projectile(val fx: Float, val fy: Float, val tx: Float, val ty: Float, val kind: FxKind, val delay: Float) {
        var t = -delay
    }
    private class Pulse(val x: Float, val y: Float, val color: Int, var t: Float = 0f)

    class FloatText(val x: Float, val y: Float, val text: String, val kind: FxKind, var age: Float)

    private val anims = IdentityHashMap<Creature, Anim>()
    private val ghosts = mutableListOf<Ghost>()
    private val projectiles = mutableListOf<Projectile>()
    private val pulses = mutableListOf<Pulse>()
    private val particles = Particles(rng)
    private var torches = listOf<Point>()

    /** Sound cues timed to the animation; collected with [drainCues]. */
    private class Cue(val id: SoundId, var delay: Float)
    private val cueQueue = mutableListOf<Cue>()
    private val readyCues = mutableListOf<SoundId>()

    fun drainCues(): List<SoundId> = readyCues.toList().also { readyCues.clear() }

    private fun cue(id: SoundId, delay: Float = 0f) {
        if (delay <= 0f) readyCues += id else cueQueue += Cue(id, delay)
    }

    /** Floating combat text for the UI layer to draw (world-pixel positions). */
    val floatTexts = mutableListOf<FloatText>()

    private val sprites = FloatList(4096)
    private val emissive = FloatList(4096)
    private val lights = FloatList(512)

    private val pixel = atlas.id("pixel")
    private val shadow = atlas.id("shadow")
    private val orb = atlas.id("orb")

    fun setViewport(width: Int, height: Int, pixelScale: Int) {
        viewW = maxOf(1, width)
        viewH = maxOf(1, height)
        scale = maxOf(1, pixelScale)
    }

    val camLeft get() = camX - viewW / (2f * scale) + shakeX
    val camTop get() = camY - viewH / (2f * scale) + shakeY

    /** Screen position (relative to the map view) of a world-pixel coordinate. */
    fun toScreenX(wx: Float) = (wx - camLeft) * scale
    fun toScreenY(wy: Float) = (wy - camTop) * scale

    fun screenToTileX(sx: Float) = floor((camLeft + sx / scale) / TILE).toInt()
    fun screenToTileY(sy: Float) = floor((camTop + sy / scale) / TILE).toInt()

    /** Smoothed top-left world position of a creature, or its square if not tracked. */
    fun displayX(c: Creature) = anims[c]?.x ?: (c.x * TILE).toFloat()
    fun displayY(c: Creature) = anims[c]?.y ?: (c.y * TILE).toFloat()

    // =======================================================================
    // Update
    // =======================================================================

    fun update(g: Game, fx: List<Fx>, dtSeconds: Float) {
        val dt = dtSeconds.coerceIn(0f, 0.05f)
        time += dt
        if (g !== game || g.depth != depth) reset(g)

        val heroes = g.livingHeroes
        val visibleMonsters = g.monsters
        val present = IdentityHashMap<Creature, Boolean>()
        for (c in heroes + visibleMonsters) {
            present[c] = true
            val a = anims.getOrPut(c) { Anim((c.x * TILE).toFloat(), (c.y * TILE).toFloat()) }
            val tx = (c.x * TILE).toFloat()
            val ty = (c.y * TILE).toFloat()
            val dx = tx - a.x
            val dy = ty - a.y
            val dist = sqrt(dx * dx + dy * dy)
            a.moving = dist > 0.5f
            if (dist > TILE * 3) {
                a.x = tx; a.y = ty
            } else if (dist > 0f) {
                val step = MOVE_SPEED * dt
                if (step >= dist) { a.x = tx; a.y = ty } else { a.x += dx / dist * step; a.y += dy / dist * step }
            }
            if (a.flashDelay > 0f) {
                a.flashDelay -= dt
                if (a.flashDelay <= 0f) a.flash = 1f
            } else {
                a.flash = maxOf(0f, a.flash - dt * 6f)
            }
            a.lungeT = maxOf(0f, a.lungeT - dt)
        }
        // Whoever disappeared died: fade them out in a puff of dust.
        val gone = anims.keys.filter { it !in present }
        for (c in gone) {
            val a = anims.remove(c)!!
            if (c is Monster && g.dungeon.isVisible(c.x, c.y)) {
                ghosts += Ghost(atlas.id(Atlas.monsterKey(c.type)), a.x, a.y)
                particles.dust(a.x + 8, a.y + 10, 14)
                cue(SoundId.MONSTER_DIE, 0.05f)
            } else if (c is Hero) {
                particles.dust(a.x + 8, a.y + 10, 18)
            }
        }

        handleFx(g, fx)
        val cit = cueQueue.iterator()
        while (cit.hasNext()) {
            val c = cit.next()
            c.delay -= dt
            if (c.delay <= 0f) {
                readyCues += c.id
                cit.remove()
            }
        }

        val pit = projectiles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            p.t += dt
            if (p.t < 0f) continue
            val k = (p.t / PROJECTILE_TIME).coerceAtMost(1f)
            val x = p.fx + (p.tx - p.fx) * k
            val y = p.fy + (p.ty - p.fy) * k
            when (p.kind) {
                FxKind.FIRE -> repeat(2) { particles.ember(x, y) }
                FxKind.RADIANT -> if (rng.nextFloat() < 0.6f) particles.motes(x, y, 0xFFF0A0, 1, 2f)
                FxKind.FORCE -> if (rng.nextFloat() < 0.7f) particles.burst(x, y, 0xB070FF, 1, 12f, 0f, 0.3f)
                FxKind.LIGHTNING -> particles.burst(x, y, 0xB8ECFF, 2, 30f, 0f, 0.15f)
                FxKind.NECROTIC -> if (rng.nextFloat() < 0.8f) particles.motes(x, y, 0x7CFF9A, 1, 3f)
                else -> {}
            }
            if (p.t >= PROJECTILE_TIME) {
                impact(p.tx, p.ty, p.kind)
                pit.remove()
            }
        }
        ghosts.forEach { it.t += dt }
        ghosts.removeAll { it.t > 0.6f }
        pulses.forEach { it.t += dt }
        pulses.removeAll { it.t > 0.4f }
        floatTexts.forEach { it.age += dt }
        floatTexts.removeAll { it.age > 1.0f }

        // Torches shed the occasional ember.
        for (t in torches) {
            if (g.dungeon.isVisible(t.x, t.y) && rng.nextFloat() < dt * 3f) {
                particles.ember(t.x * TILE + 8f, t.y * TILE + 4f)
            }
        }
        particles.update(dt)

        updateCamera(g, dt)
        shake *= exp(-dt * 9f)
        shakeX = if (shake > 0.2f) (rng.nextFloat() * 2 - 1) * shake else 0f
        shakeY = if (shake > 0.2f) (rng.nextFloat() * 2 - 1) * shake else 0f
    }

    private fun reset(g: Game) {
        game = g
        depth = g.depth
        anims.clear()
        ghosts.clear()
        projectiles.clear()
        pulses.clear()
        particles.clear()
        floatTexts.clear()
        cueQueue.clear()
        val d = g.dungeon
        torches = buildList {
            for (y in 0 until d.height) for (x in 0 until d.width) {
                if (d[x, y] == Tile.WALL && d[x, y + 1].walkable && hash(x, y) % 6 == 0 &&
                    none { abs(it.x - x) + abs(it.y - y) < 4 }
                ) add(Point(x, y))
            }
        }
        val (fx, fy) = focus(g)
        camX = fx; camY = fy
    }

    private fun creatureAt(g: Game, x: Int, y: Int): Creature? = g.heroAt(x, y) ?: g.monsterAt(x, y)

    private fun handleFx(g: Game, fx: List<Fx>) {
        val boltTargets = fx.filterIsInstance<Fx.Bolt>().map { it.toX to it.toY }.toSet()
        var boltIndex = 0
        for (f in fx) when (f) {
            is Fx.Bolt -> {
                projectiles += Projectile(f.fromX * TILE + 8f, f.fromY * TILE + 8f, f.toX * TILE + 8f, f.toY * TILE + 8f,
                    f.kind, boltIndex * 0.06f)
                if (boltIndex < 3) cue(when (f.kind) {
                    FxKind.ARROW -> SoundId.BOW
                    FxKind.FIRE -> SoundId.FIRE_CAST
                    FxKind.RADIANT -> SoundId.HOLY
                    FxKind.LIGHTNING -> SoundId.ZAP
                    FxKind.NECROTIC -> SoundId.NECRO
                    else -> SoundId.MAGIC
                }, boltIndex * 0.06f)
                boltIndex++
            }
            is Fx.Text -> {
                val ranged = (f.x to f.y) in boltTargets
                val delay = if (ranged) PROJECTILE_TIME else 0.08f
                floatTexts += FloatText(f.x * TILE + 8f, f.y * TILE + 4f, f.text, f.kind, -delay - 0.05f)
                val target = creatureAt(g, f.x, f.y)
                val targetAnim = target?.let { anims[it] }
                textCue(f, delay)?.let { cue(it, delay) }
                when (f.kind) {
                    FxKind.DAMAGE, FxKind.CRIT -> {
                        targetAnim?.flashDelay = delay
                        shake = maxOf(shake, if (f.kind == FxKind.CRIT) 5f else if (target is Hero) 2.5f else 1.2f)
                        if (!ranged) particles.burst(f.x * TILE + 8f, f.y * TILE + 8f,
                            if (f.kind == FxKind.CRIT) 0xFFD040 else 0xFF5050, if (f.kind == FxKind.CRIT) 18 else 8)
                    }
                    FxKind.HEAL -> {
                        particles.motes(f.x * TILE + 8f, f.y * TILE + 10f, 0x60F080, if (f.text == "LEVEL UP") 0 else 14)
                        pulses += Pulse(f.x * TILE + 8f, f.y * TILE + 8f, 0x60F080)
                        if (f.text == "LEVEL UP") {
                            particles.motes(f.x * TILE + 8f, f.y * TILE + 10f, 0xFFD040, 24, 8f)
                            pulses += Pulse(f.x * TILE + 8f, f.y * TILE + 8f, 0xFFD040)
                        }
                    }
                    FxKind.INFO -> particles.burst(f.x * TILE + 8f, f.y * TILE + 8f, 0xFFD040, 8, 25f, 40f)
                    FxKind.LOOT -> {
                        particles.motes(f.x * TILE + 8f, f.y * TILE + 10f, 0xC080FF, 20, 7f)
                        pulses += Pulse(f.x * TILE + 8f, f.y * TILE + 8f, 0xC080FF)
                    }
                    else -> {}
                }
                // Melee attacker lunges at the target.
                if (!ranged && f.kind in setOf(FxKind.DAMAGE, FxKind.CRIT, FxKind.MISS)) {
                    val attacker = g.current
                    if (attacker != null && attacker !== target && chebyshevTo(attacker, f.x, f.y) == 1) {
                        anims[attacker]?.let {
                            it.lungeX = (f.x - attacker.x).toFloat()
                            it.lungeY = (f.y - attacker.y).toFloat()
                            it.lungeT = LUNGE_TIME
                        }
                    }
                }
            }
        }
    }

    private fun chebyshevTo(c: Creature, x: Int, y: Int) = maxOf(abs(c.x - x), abs(c.y - y))

    /** The sound for a floating combat text, if any. */
    private fun textCue(f: Fx.Text, delay: Float): SoundId? = when (f.kind) {
        FxKind.DAMAGE -> SoundId.HIT
        FxKind.CRIT -> SoundId.CRIT
        FxKind.MISS -> if (f.text == "miss") SoundId.MISS else null
        FxKind.HEAL -> when (f.text) {
            "LEVEL UP" -> SoundId.LEVEL_UP
            "revived", "rested" -> null
            else -> if (f.text.endsWith("HD")) null else SoundId.HEAL
        }
        FxKind.LOOT -> SoundId.LOOT
        FxKind.NECROTIC -> if (f.text == "rise!") SoundId.RAISE else null
        FxKind.RADIANT -> if (f.text == "blessed") SoundId.HOLY else null
        FxKind.INFO -> when {
            f.text.startsWith("+") && f.text.endsWith("g") -> SoundId.COIN
            f.text == "Zzz" -> SoundId.SLEEP
            f.text == "challenged" -> SoundId.CHALLENGE
            f.text == "marked" -> SoundId.MARK
            else -> null
        }
        else -> null
    }

    private fun impact(x: Float, y: Float, kind: FxKind) {
        when (kind) {
            FxKind.FIRE -> cue(SoundId.EXPLOSION)
            FxKind.LIGHTNING -> cue(SoundId.THUNDER)
            else -> {}
        }
        val color = colorOf(kind)
        when (kind) {
            FxKind.ARROW -> particles.burst(x, y, 0xD8C8A0, 5, 30f)
            FxKind.FIRE -> {
                particles.burst(x, y, 0xFF8A20, 22, 70f, 30f, 0.5f)
                repeat(6) { particles.ember(x, y) }
            }
            else -> particles.burst(x, y, color, 14, 55f, 20f)
        }
        if (kind != FxKind.ARROW) pulses += Pulse(x, y, color)
    }

    private fun focus(g: Game): Pair<Float, Float> {
        val cur = g.current
        val target: Creature = if (g.phase == Phase.MONSTER_TURN && cur is Monster && g.dungeon.isVisible(cur.x, cur.y)) cur
        else g.activeHero ?: g.livingHeroes.firstOrNull() ?: g.party.first()
        return displayX(target) + 8f to displayY(target) + 8f
    }

    private fun updateCamera(g: Game, dt: Float) {
        val (fx, fy) = focus(g)
        val k = 1f - exp(-dt * 6f)
        camX += (fx - camX) * k
        camY += (fy - camY) * k
    }

    // =======================================================================
    // Frame building
    // =======================================================================

    private fun sprite(out: FloatList, id: Int, x: Float, y: Float, w: Float = 16f, h: Float = 16f,
                       r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f, flash: Float = 0f, rotate: Boolean = false) {
        out.add(id.toFloat()); out.add(x); out.add(y); out.add(w); out.add(h)
        out.add(r); out.add(g); out.add(b); out.add(a); out.add(flash); out.add(if (rotate) 1f else 0f)
    }

    private fun light(x: Float, y: Float, radius: Float, color: Int, intensity: Float) {
        lights.add(x); lights.add(y); lights.add(radius)
        lights.add(((color shr 16) and 255) / 255f)
        lights.add(((color shr 8) and 255) / 255f)
        lights.add((color and 255) / 255f)
        lights.add(intensity)
    }

    private fun flicker(seed: Int) =
        1f + 0.05f * sin(time * 11f + seed) + 0.035f * sin(time * 23.7f + seed * 2.3f) + 0.02f * sin(time * 5.1f + seed)

    fun buildFrame(g: Game): WorldFrame {
        sprites.clear()
        emissive.clear()
        lights.clear()
        val d = g.dungeon
        val left = floor(camLeft / TILE).toInt() - 1
        val top = floor(camTop / TILE).toInt() - 1
        val right = left + viewW / (scale * TILE) + 3
        val bottom = top + viewH / (scale * TILE) + 3

        // Terrain
        val wallTop = atlas.id("wall_top")
        val wallFront = atlas.id("wall")
        val wallShadow = atlas.id("wall_shadow")
        val floors = intArrayOf(atlas.id("floor"), atlas.id("floor_cracked"), atlas.id("floor_moss"), atlas.id("floor_rubble"))
        val stairs = atlas.id("stairs")
        val torchFrames = intArrayOf(atlas.id("torch_a"), atlas.id("torch_b"))
        for (y in top..bottom) for (x in left..right) {
            if (!d.isExplored(x, y)) continue
            val px = (x * TILE).toFloat()
            val py = (y * TILE).toFloat()
            when (d[x, y]) {
                Tile.WALL -> sprite(sprites, if (d[x, y + 1].walkable) wallFront else wallTop, px, py)
                Tile.FLOOR -> {
                    val h = hash(x, y) % 100
                    sprite(sprites, floors[if (h < 82) 0 else 1 + h % 3], px, py)
                }
                Tile.STAIRS -> {
                    sprite(sprites, stairs, px, py)
                    light(px + 8, py + 8, 36f, 0x6A9CFF, 0.7f * flicker(x * 7))
                }
            }
            if (d[x, y].walkable && d[x, y - 1] == Tile.WALL) sprite(sprites, wallShadow, px, py)
        }
        torches.forEachIndexed { i, t ->
            if (!d.isExplored(t.x, t.y)) return@forEachIndexed
            val frame = ((time * 7f + i * 1.7f).toInt()) % 2
            sprite(sprites, torchFrames[frame], t.x * TILE.toFloat(), t.y * TILE.toFloat())
            light(t.x * TILE + 8f, t.y * TILE + 12f, 70f * flicker(i * 13), 0xFF8A3C, 0.9f)
        }

        // Things on the floor
        val bones = atlas.id("bones")
        val grave = atlas.id("grave")
        for (p in g.corpses) if (d.isExplored(p.x, p.y)) sprite(sprites, bones, p.x * TILE.toFloat(), p.y * TILE.toFloat())
        for (p in g.graves) sprite(sprites, grave, p.x * TILE.toFloat(), p.y * TILE.toFloat())
        for (item in d.items) {
            if (!d.isExplored(item.x, item.y)) continue
            val px = item.x * TILE.toFloat()
            val py = item.y * TILE.toFloat()
            val id = when (item.kind) {
                ItemKind.CHEST -> atlas.id("chest")
                ItemKind.POTION -> atlas.id("potion")
                ItemKind.GOLD -> atlas.id("gold")
                ItemKind.TREASURE -> atlas.id("treasure")
            }
            val bobs = item.kind == ItemKind.POTION || item.kind == ItemKind.TREASURE
            val bob = if (bobs) floor(sin(time * 3f + item.x) * 1.2f) else 0f
            sprite(sprites, id, px, py + bob)
            if (item.kind == ItemKind.POTION) light(px + 8, py + 9, 14f, 0xFF4040, 0.5f)
            if (item.kind == ItemKind.TREASURE && d.isVisible(item.x, item.y)) {
                val rarity = item.item?.rarity?.color ?: 0xFFFFFFFF.toInt()
                light(px + 9, py + 8, 22f, rarity and 0xFFFFFF, 0.9f * flicker(item.x * 5 + item.y))
                if (rng.nextFloat() < 0.04f) particles.motes(px + 10, py + 7, rarity and 0xFFFFFF, 1, 3f)
            }
        }

        // Creatures, back to front
        data class Draw(val c: Creature, val x: Float, val y: Float)
        val draws = mutableListOf<Draw>()
        for (h in g.livingHeroes) draws += Draw(h, displayX(h), displayY(h))
        for (m in g.monsters) if (d.isVisible(m.x, m.y)) draws += Draw(m, displayX(m), displayY(m))
        draws.sortBy { it.y }
        for (dr in draws) sprite(sprites, shadow, dr.x, dr.y + 1f, a = 0.9f)
        for (ghost in ghosts) {
            val a = 1f - ghost.t / 0.6f
            sprite(sprites, ghost.sprite, ghost.x, ghost.y - ghost.t * 10f, a = a, flash = 0.5f * a)
        }
        for ((c, x, y) in draws) {
            val a = anims[c]
            val lunge = a?.let { if (it.lungeT > 0f) sin((1f - it.lungeT / LUNGE_TIME) * Math.PI.toFloat()) * 4f else 0f } ?: 0f
            val lx = (a?.lungeX ?: 0f) * lunge
            val ly = (a?.lungeY ?: 0f) * lunge
            val flash = a?.flash ?: 0f
            when (c) {
                is Hero -> {
                    val idx = g.party.indexOf(c)
                    val bob = if (a?.moving == true || c.isUnconscious) 0f else if (sin(time * 3.4f + idx * 1.6f) > 0.3f) -1f else 0f
                    val s = HeroArt.scale(c.race)
                    val w = floor(16f * s)
                    sprite(sprites, atlas.id(Atlas.heroKey(c.race, c.charClass)),
                        x + lx + (16f - w) / 2, y + ly + 16f - w + bob, w, w, flash = flash, rotate = c.isUnconscious,
                        r = if (c.isUnconscious) 0.75f else 1f, g = if (c.isUnconscious) 0.7f else 1f, b = if (c.isUnconscious) 0.7f else 1f)
                    if (!c.isUnconscious) {
                        light(x + 8, y + 8, 92f * flicker(idx * 31), 0xFFB070, 1.0f)
                    }
                }
                is Monster -> {
                    val bob = if (a?.moving == true) 0f else if (sin(time * 2.6f + c.x * 1.3f + c.y) > 0.5f) -1f else 0f
                    // The party's undead minions glow a ghostly green.
                    val tint = if (c.ally) floatArrayOf(0.7f, 1f, 0.8f) else floatArrayOf(1f, 1f, 1f)
                    sprite(sprites, atlas.id(Atlas.monsterKey(c.type)), x + lx, y + ly + bob, flash = flash,
                        r = tint[0], g = tint[1], b = tint[2])
                    if (c.ally) light(x + 8, y + 8, 26f, 0x60FF90, 0.6f)
                    if (c.marked) {
                        light(x + 8, y + 8, 24f, 0xFFE070, 0.8f)
                        if (rng.nextFloat() < 0.15f) particles.motes(x + 8, y + 8, 0xFFF0A0, 1, 5f)
                    }
                }
            }
        }

        // Glowing things drawn after lighting
        for (p in projectiles) {
            if (p.t < 0f) continue
            val k = (p.t / PROJECTILE_TIME).coerceAtMost(1f)
            val x = p.fx + (p.tx - p.fx) * k
            val y = p.fy + (p.ty - p.fy) * k
            val c = colorOf(p.kind)
            val r = ((c shr 16) and 255) / 255f
            val gg = ((c shr 8) and 255) / 255f
            val b = (c and 255) / 255f
            if (p.kind == FxKind.ARROW) {
                sprite(emissive, pixel, x - 1.5f, y - 0.5f, 3f, 1f, r, gg, b, 0.9f)
            } else {
                sprite(emissive, orb, x - 6f, y - 6f, 12f, 12f, r, gg, b, 1f)
                light(x, y, 44f, c, 1.3f)
            }
        }
        particles.emit(emissive, pixel)
        for (p in pulses) {
            val k = 1f - p.t / 0.4f
            light(p.x, p.y, 40f + 30f * (1f - k), p.color, 1.6f * k)
        }

        // Fog of war
        val vis = ByteArray(d.width * d.height * 2)
        for (y in 0 until d.height) for (x in 0 until d.width) {
            val i = (y * d.width + x) * 2
            if (d.isVisible(x, y)) vis[i] = 255.toByte()
            if (d.isExplored(x, y)) vis[i + 1] = 255.toByte()
        }

        return WorldFrame(
            viewW, viewH, scale, camLeft, camTop,
            sprites.toArray(), sprites.size / WorldFrame.SPRITE_STRIDE,
            emissive.toArray(), emissive.size / WorldFrame.SPRITE_STRIDE,
            lights.toArray(), lights.size / WorldFrame.LIGHT_STRIDE,
            vis, d.width, d.height,
            floatArrayOf(0.30f, 0.28f, 0.38f),
        )
    }

    companion object {
        const val TILE = 16
        const val MOVE_SPEED = 150f // world pixels per second
        const val PROJECTILE_TIME = 0.3f
        const val LUNGE_TIME = 0.18f

        fun colorOf(kind: FxKind): Int = when (kind) {
            FxKind.FIRE -> 0xFF8A20
            FxKind.RADIANT -> 0xFFF0A0
            FxKind.FORCE -> 0xB070FF
            FxKind.ARROW -> 0xD8C8A0
            FxKind.HEAL -> 0x60F080
            FxKind.CRIT -> 0xFFD040
            FxKind.DAMAGE -> 0xFF5050
            FxKind.MISS -> 0xB0B0C0
            FxKind.INFO -> 0xFFD040
            FxKind.LOOT -> 0xC080FF
            FxKind.LIGHTNING -> 0x8AD8FF
            FxKind.NECROTIC -> 0x7CFF9A
        }

        /** Stable per-square pseudo-random number for decoration choices. */
        fun hash(x: Int, y: Int): Int {
            var h = (x * 73856093) xor (y * 19349663) xor 0x5BD1E995
            h = (h xor (h ushr 16)) * -0x7a143595
            h = (h xor (h ushr 13)) * -0x3d4d51cb
            return (h xor (h ushr 16)) and 0x7FFFFFFF
        }
    }
}
