package com.rpg1.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.rpg1.core.Ability
import com.rpg1.core.CharClass
import com.rpg1.core.Dungeon
import com.rpg1.core.Fx
import com.rpg1.core.FxKind
import com.rpg1.core.Game
import com.rpg1.core.Hero
import com.rpg1.core.ItemKind
import com.rpg1.core.LogKind
import com.rpg1.core.Monster
import com.rpg1.core.Phase
import com.rpg1.core.Premades
import com.rpg1.core.Race
import com.rpg1.core.SpellTarget
import com.rpg1.core.Tile
import com.rpg1.core.art.Sprites
import com.rpg1.core.formatModifier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws and drives the whole game on a Canvas: the party builder and the dungeon.
 * All rules live in the core module; this class only renders state and forwards taps.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val host: Host) : View(context), Choreographer.FrameCallback {

    interface Host {
        fun askName(current: String, onResult: (String) -> Unit)
    }

    private enum class Screen { PARTY, PLAY }

    private var screen = if (GameSession.game != null) Screen.PLAY else Screen.PARTY

    // --- Paints -------------------------------------------------------------
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val fill = Paint().apply { isAntiAlias = false }
    private val stroke = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = false }
    private val text = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.WHITE
    }

    // --- Colors -------------------------------------------------------------
    private val cBg = 0xFF0E0B14.toInt()
    private val cPanel = 0xFF1C1626.toInt()
    private val cPanelLight = 0xFF2A2238.toInt()
    private val cButton = 0xFF3A2F4E.toInt()
    private val cButtonOn = 0xFF8A5A1E.toInt()
    private val cButtonOff = 0xFF231D2E.toInt()
    private val cGold = 0xFFF2C641.toInt()
    private val cText = 0xFFEDE6F5.toInt()
    private val cDim = 0xFF8C82A0.toInt()
    private val cHp = 0xFF4CC650.toInt()
    private val cHpLow = 0xFFE04040.toInt()

    // --- Layout & input -----------------------------------------------------
    private class Hit(val rect: RectF, val action: () -> Unit)
    private val hits = mutableListOf<Hit>()
    private var downX = 0f
    private var downY = 0f

    private var mapW = 0
    private var tilePx = 64
    private var camX = 0f
    private var camY = 0f
    private var camReady = false

    // --- Timing & effects ---------------------------------------------------
    private var lastTick = 0L
    private var nowMs = 0L
    private class LiveFx(val fx: Fx, val born: Long)
    private val liveFx = mutableListOf<LiveFx>()

    private var logExpanded = false
    private var sheetHero: Hero? = null
    private val premadeIndex = IntArray(4) { -1 }
    private var premadePartyIndex = 0

    private val game: Game? get() = GameSession.game

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        nowMs = frameTimeNanos / 1_000_000
        game?.let { g ->
            if (screen == Screen.PLAY) {
                val interval = when {
                    g.phase == Phase.MONSTER_TURN -> 240L
                    g.inCombat -> 150L
                    else -> 105L
                }
                if (nowMs - lastTick >= interval) {
                    lastTick = nowMs
                    g.tick()
                }
                g.drainFx().forEach { liveFx += LiveFx(it, nowMs) }
                liveFx.removeAll { nowMs - it.born > 1000 }
            }
        }
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Returns true if the back press was consumed. */
    fun handleBack(): Boolean {
        if (sheetHero != null) { sheetHero = null; return true }
        if (logExpanded) { logExpanded = false; return true }
        return false
    }

    // =======================================================================
    // Input
    // =======================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > tilePx || abs(e.y - downY) > tilePx) return true
                handleTap(e.x, e.y)
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        // Topmost regions are added last.
        hits.lastOrNull { it.rect.contains(x, y) }?.let {
            it.action()
            return
        }
        val g = game ?: return
        if (screen != Screen.PLAY || x >= mapW) return
        val tx = Math.floorDiv((x - mapW / 2f + camX * tilePx).toInt(), tilePx)
        val ty = Math.floorDiv((y - height / 2f + camY * tilePx).toInt(), tilePx)
        g.tap(tx, ty)
    }

    private fun hit(rect: RectF, action: () -> Unit) {
        hits += Hit(RectF(rect), action)
    }

    // =======================================================================
    // Drawing
    // =======================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        mapW = (w * 0.66f).toInt()
        tilePx = 16 * max(2, h / (16 * 10))
    }

    override fun onDraw(canvas: Canvas) {
        hits.clear()
        canvas.drawColor(cBg)
        when (screen) {
            Screen.PARTY -> drawPartyScreen(canvas)
            Screen.PLAY -> game?.let { drawPlay(canvas, it) } ?: run { screen = Screen.PARTY }
        }
    }

    /** Splits [s] into lines no wider than [maxWidth] at [size]. */
    private fun wrap(s: String, size: Float, maxWidth: Float): List<String> {
        text.textSize = size
        val out = mutableListOf<String>()
        var rest = s
        while (rest.isNotEmpty()) {
            var n = text.breakText(rest, true, maxWidth, null)
            if (n < rest.length) {
                val space = rest.lastIndexOf(' ', n)
                if (space > 0) n = space
            }
            out += rest.substring(0, n).trimEnd()
            rest = rest.substring(n).trimStart()
        }
        return out
    }

    private fun drawText(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = cText, align: Paint.Align = Paint.Align.LEFT) {
        text.textSize = size
        text.color = color
        text.textAlign = align
        c.drawText(s, x, y, text)
    }

    /** Draws [s] shrunk to fit [maxWidth]. */
    private fun drawFitText(c: Canvas, s: String, x: Float, y: Float, size: Float, maxWidth: Float, color: Int = cText, align: Paint.Align = Paint.Align.LEFT) {
        text.textSize = size
        val w = text.measureText(s)
        drawText(c, s, x, y, if (w > maxWidth) size * maxWidth / w else size, color, align)
    }

    private fun box(c: Canvas, r: RectF, color: Int, border: Int? = null, borderWidth: Float = 3f) {
        fill.color = color
        c.drawRect(r, fill)
        if (border != null) {
            stroke.color = border
            stroke.strokeWidth = borderWidth
            c.drawRect(r, stroke)
        }
    }

    private fun button(c: Canvas, r: RectF, label: String, enabled: Boolean = true, active: Boolean = false, action: () -> Unit) {
        val bg = when {
            active -> cButtonOn
            enabled -> cButton
            else -> cButtonOff
        }
        box(c, r, bg, if (active) cGold else 0xFF4E4266.toInt(), 3f)
        drawFitText(c, label, r.centerX(), r.centerY() + r.height() * 0.17f, r.height() * 0.42f, r.width() * 0.9f,
            if (enabled) cText else cDim, Paint.Align.CENTER)
        if (enabled) hit(r, action)
    }

    private fun sprite(c: Canvas, bmp: Bitmap, left: Float, top: Float, size: Float, scale: Float = 1f) {
        val s = size * scale
        val r = RectF(left + (size - s) / 2, top + size - s, left + (size + s) / 2, top + size)
        c.drawBitmap(bmp, null, r, pixelPaint)
    }

    private fun heroSprite(c: Canvas, h: Hero, left: Float, top: Float, size: Float) =
        sprite(c, SpriteCache.hero(h.race, h.charClass), left, top, size, SpriteCache.heroScale(h.race))

    // =======================================================================
    // Party builder
    // =======================================================================

    private fun drawPartyScreen(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val builder = GameSession.builder
        val pad = h * 0.025f
        drawText(c, "D20 DUNGEON", w / 2, h * 0.09f, h * 0.07f, cGold, Paint.Align.CENTER)
        drawText(c, "Assemble your party of four", w / 2, h * 0.14f, h * 0.035f, cDim, Paint.Align.CENTER)

        val cardTop = h * 0.17f
        val cardBottom = h * 0.85f
        val cardW = (w - pad * 5) / 4
        builder.drafts.forEachIndexed { i, draft ->
            val left = pad + i * (cardW + pad)
            val card = RectF(left, cardTop, left + cardW, cardBottom)
            box(c, card, cPanel, 0xFF4E4266.toInt())
            val hero = draft.build()
            val cx = card.centerX()
            val line = (cardBottom - cardTop) / 15f
            var y = cardTop + line * 0.3f

            val spriteSize = min(cardW * 0.42f, line * 3.4f)
            heroSprite(c, hero, cx - spriteSize / 2, y, spriteSize)
            y += spriteSize + line * 0.9f

            // Name (tap to edit)
            val nameRect = RectF(left + pad, y - line * 0.8f, card.right - pad, y + line * 0.35f)
            box(c, nameRect, cPanelLight)
            drawFitText(c, draft.name, cx, y, line * 0.75f, nameRect.width() * 0.9f, cGold, Paint.Align.CENTER)
            hit(nameRect) { host.askName(draft.name) { draft.name = it } }
            y += line * 0.75f
            drawText(c, "tap name to rename", cx, y, line * 0.38f, cDim, Paint.Align.CENTER)
            y += line * 1.0f

            fun cycler(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
                val bw = line * 1.1f
                button(c, RectF(left + pad, y - line * 0.8f, left + pad + bw, y + line * 0.3f), "◀", action = onPrev)
                button(c, RectF(card.right - pad - bw, y - line * 0.8f, card.right - pad, y + line * 0.3f), "▶", action = onNext)
                drawFitText(c, label, cx, y, line * 0.65f, cardW - bw * 2 - pad * 3, cText, Paint.Align.CENTER)
                y += line * 1.3f
            }
            cycler(draft.race.displayName,
                { draft.race = Race.entries[(draft.race.ordinal + Race.entries.size - 1) % Race.entries.size] },
                { draft.race = Race.entries[(draft.race.ordinal + 1) % Race.entries.size] })
            cycler(draft.charClass.displayName,
                { draft.charClass = CharClass.entries[(draft.charClass.ordinal + CharClass.entries.size - 1) % CharClass.entries.size] },
                { draft.charClass = CharClass.entries[(draft.charClass.ordinal + 1) % CharClass.entries.size] })

            // Ability scores in two columns
            Ability.entries.forEachIndexed { ai, ability ->
                val col = ai / 3
                val row = ai % 3
                val ax = left + pad + col * (cardW - pad * 2) / 2
                val ay = y + row * line * 0.95f
                val score = hero.abilities[ability]
                drawText(c, ability.name, ax, ay, line * 0.55f, cDim)
                drawText(c, "$score", ax + line * 2.0f, ay, line * 0.6f, cText)
                drawText(c, "(${formatModifier(hero.abilities.mod(ability))})", ax + line * 3.1f, ay, line * 0.5f, cDim)
            }
            y += line * 3.0f
            drawFitText(c, "HP ${hero.maxHp}  AC ${hero.armorClass}  Spd ${hero.speed * 5}ft", cx, y, line * 0.55f,
                cardW - pad * 2, cText, Paint.Align.CENTER)
            y += line * 0.8f
            drawFitText(c, draft.race.trait.substringBefore(':'), cx, y, line * 0.45f, cardW - pad * 2, cDim, Paint.Align.CENTER)
            y += line * 0.7f

            val bw = (cardW - pad * 3) / 2
            button(c, RectF(left + pad, y, left + pad + bw, y + line * 1.1f), "Reroll") { builder.reroll(i) }
            button(c, RectF(left + pad * 2 + bw, y, card.right - pad, y + line * 1.1f), "Premade") {
                if (Premades.heroes.isNotEmpty()) {
                    premadeIndex[i] = (premadeIndex[i] + 1) % Premades.heroes.size
                    builder.applyHero(i, Premades.heroes[premadeIndex[i]])
                }
            }
        }

        val by = h * 0.875f
        val bh = h * 0.09f
        if (Premades.parties.isNotEmpty()) {
            val party = Premades.parties[premadePartyIndex % Premades.parties.size]
            button(c, RectF(pad, by, w * 0.5f - pad / 2, by + bh), "Premade party: ${party.name}") {
                builder.applyParty(party)
                premadePartyIndex = (premadePartyIndex + 1) % Premades.parties.size
            }
        }
        button(c, RectF(w * 0.5f + pad / 2, by, w - pad, by + bh), "Begin Adventure ▶", active = true) {
            GameSession.startGame()
            liveFx.clear()
            camReady = false
            logExpanded = false
            screen = Screen.PLAY
        }
    }

    // =======================================================================
    // Dungeon
    // =======================================================================

    private fun drawPlay(c: Canvas, g: Game) {
        updateCamera(g)
        c.save()
        c.clipRect(0f, 0f, mapW.toFloat(), height.toFloat())
        drawMap(c, g)
        drawFx(c, g)
        drawMapOverlay(c, g)
        c.restore()
        drawPanel(c, g)
        sheetHero?.let { drawSheet(c, it) }
        if (g.phase == Phase.GAME_OVER) drawGameOver(c, g)
    }

    private fun focusOf(g: Game): Pair<Int, Int> {
        val cur = g.current
        if (g.phase == Phase.MONSTER_TURN && cur is Monster && g.dungeon.isVisible(cur.x, cur.y)) return cur.x to cur.y
        val h = g.activeHero ?: g.livingHeroes.firstOrNull() ?: g.party.first()
        return h.x to h.y
    }

    private fun updateCamera(g: Game) {
        val (fx, fy) = focusOf(g)
        val tx = fx + 0.5f
        val ty = fy + 0.5f
        if (!camReady || abs(tx - camX) > 20 || abs(ty - camY) > 20) {
            camX = tx; camY = ty; camReady = true
        } else {
            camX += (tx - camX) * 0.15f
            camY += (ty - camY) * 0.15f
        }
    }

    private fun screenX(tx: Float) = mapW / 2f + (tx - camX) * tilePx
    private fun screenY(ty: Float) = height / 2f + (ty - camY) * tilePx
    private fun tileRect(x: Int, y: Int) = RectF(screenX(x.toFloat()), screenY(y.toFloat()),
        screenX(x.toFloat()) + tilePx, screenY(y.toFloat()) + tilePx)

    private fun drawMap(c: Canvas, g: Game) {
        val d = g.dungeon
        val halfW = mapW / tilePx / 2 + 2
        val halfH = height / tilePx / 2 + 2
        val cx = camX.toInt()
        val cy = camY.toInt()
        val fog = 0xB0000000.toInt()
        val floor = SpriteCache.get(Sprites.FLOOR)
        val wall = SpriteCache.get(Sprites.WALL)
        val stairs = SpriteCache.get(Sprites.STAIRS)

        for (y in cy - halfH..cy + halfH) for (x in cx - halfW..cx + halfW) {
            if (!d.isExplored(x, y)) continue
            val r = tileRect(x, y)
            c.drawBitmap(when (d[x, y]) {
                Tile.WALL -> wall
                Tile.FLOOR -> floor
                Tile.STAIRS -> stairs
            }, null, r, pixelPaint)
        }

        // Reachable squares for the active hero in combat.
        if (g.phase == Phase.PLAYER_TURN && g.movementLeft > 0 && g.armedSpell == null && !g.armedRanged && !g.armedPotion) {
            g.activeHero?.takeIf { !it.isUnconscious }?.let { h ->
                fill.color = 0x302E7CF0
                for ((p, _) in reachable(g, h)) c.drawRect(tileRect(p.first, p.second), fill)
            }
        }

        for (p in g.corpses) if (d.isExplored(p.x, p.y)) c.drawBitmap(SpriteCache.get(Sprites.BONES), null, tileRect(p.x, p.y), pixelPaint)
        for (p in g.graves) c.drawBitmap(SpriteCache.get(Sprites.GRAVE), null, tileRect(p.x, p.y), pixelPaint)
        for (item in d.items) {
            if (!d.isExplored(item.x, item.y)) continue
            val s = when (item.kind) {
                ItemKind.CHEST -> Sprites.CHEST
                ItemKind.POTION -> Sprites.POTION
                ItemKind.GOLD -> Sprites.GOLD
            }
            c.drawBitmap(SpriteCache.get(s), null, tileRect(item.x, item.y), pixelPaint)
        }

        // Fog over remembered-but-unseen squares.
        fill.color = fog
        for (y in cy - halfH..cy + halfH) for (x in cx - halfW..cx + halfW) {
            if (d.isExplored(x, y) && !d.isVisible(x, y)) c.drawRect(tileRect(x, y), fill)
        }

        val pulse = (0.5f + 0.5f * sin(nowMs / 180.0).toFloat())
        val targeting = g.armedSpell != null || g.armedRanged
        val active = g.activeHero

        for (m in g.monsters) {
            if (!d.isVisible(m.x, m.y)) continue
            val r = tileRect(m.x, m.y)
            c.drawBitmap(SpriteCache.monster(m.type), null, r, pixelPaint)
            drawHpBar(c, r, m.hp, m.maxHp)
            if (m === g.current) outline(c, r, 0xFFE04040.toInt(), pulse)
            else if (targeting && active != null && inRangeOf(g, active, m)) outline(c, r, 0xFFFF7040.toInt(), pulse)
            if (m.marked) drawText(c, "✦", r.right - tilePx * 0.15f, r.top + tilePx * 0.3f, tilePx * 0.3f, cGold, Paint.Align.CENTER)
        }

        val allyTargeting = g.armedPotion || g.armedSpell?.target == SpellTarget.ALLY
        for (h in g.livingHeroes) {
            val r = tileRect(h.x, h.y)
            if (h.isUnconscious) {
                c.save()
                c.rotate(90f, r.centerX(), r.centerY())
                heroSprite(c, h, r.left, r.top, tilePx.toFloat())
                c.restore()
            } else {
                heroSprite(c, h, r.left, r.top, tilePx.toFloat())
            }
            drawHpBar(c, r, h.hp, h.maxHp)
            if (h === active) outline(c, r, cGold, pulse)
            else if (allyTargeting) outline(c, r, 0xFF50E070.toInt(), pulse)
            if (g.phase == Phase.EXPLORING && h === g.leader) {
                drawText(c, "★", r.centerX(), r.top + tilePx * 0.05f, tilePx * 0.3f, cGold, Paint.Align.CENTER)
            }
        }
    }

    private fun inRangeOf(g: Game, h: Hero, m: Monster): Boolean {
        val range = g.armedSpell?.range ?: h.charClass.rangedWeapon?.range ?: 1
        return h.distanceTo(m) <= range && g.dungeon.hasLineOfSight(h.x, h.y, m.x, m.y)
    }

    /** Squares the hero can reach with their remaining movement (allies can be passed, not stopped on). */
    private fun reachable(g: Game, h: Hero): Map<Pair<Int, Int>, Int> {
        val d = g.dungeon
        val dist = HashMap<Pair<Int, Int>, Int>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        val start = h.x to h.y
        dist[start] = 0
        queue += start
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            val dp = dist.getValue(p)
            if (dp >= g.movementLeft) continue
            for ((dx, dy) in Dungeon.DIRS) {
                val n = p.first + dx to p.second + dy
                if (n in dist || !d.isExplored(n.first, n.second)) continue
                if (!d.canStep(p.first, p.second, n.first, n.second) || g.monsterAt(n.first, n.second) != null) continue
                dist[n] = dp + 1
                queue += n
            }
        }
        dist.remove(start)
        return dist.filterKeys { g.heroAt(it.first, it.second) == null }
    }

    private fun outline(c: Canvas, r: RectF, color: Int, pulse: Float) {
        stroke.color = color
        stroke.alpha = (140 + 115 * pulse).toInt()
        stroke.strokeWidth = max(3f, tilePx / 16f)
        val inset = stroke.strokeWidth / 2
        c.drawRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, stroke)
        stroke.alpha = 255
    }

    private fun drawHpBar(c: Canvas, r: RectF, hp: Int, maxHp: Int) {
        if (hp >= maxHp) return
        val h = max(4f, tilePx / 14f)
        val top = r.top + 1
        fill.color = 0xC0000000.toInt()
        c.drawRect(r.left + tilePx * 0.1f, top, r.right - tilePx * 0.1f, top + h, fill)
        val frac = (hp.coerceAtLeast(0).toFloat() / maxHp)
        fill.color = if (frac < 0.35f) cHpLow else cHp
        c.drawRect(r.left + tilePx * 0.1f, top, r.left + tilePx * 0.1f + (r.width() - tilePx * 0.2f) * frac, top + h, fill)
    }

    private fun fxColor(kind: FxKind) = when (kind) {
        FxKind.DAMAGE -> 0xFFFF5050.toInt()
        FxKind.CRIT -> 0xFFFFD040.toInt()
        FxKind.HEAL -> 0xFF60F080.toInt()
        FxKind.MISS -> 0xFFB0B0C0.toInt()
        FxKind.FIRE -> 0xFFFF8A20.toInt()
        FxKind.RADIANT -> 0xFFFFF0A0.toInt()
        FxKind.FORCE -> 0xFFB070FF.toInt()
        FxKind.ARROW -> 0xFFD8C8A0.toInt()
        FxKind.INFO -> cGold
    }

    private fun drawFx(c: Canvas, g: Game) {
        for (live in liveFx) {
            val age = (nowMs - live.born).coerceAtLeast(0)
            when (val fx = live.fx) {
                is Fx.Bolt -> {
                    if (age > 320) continue
                    val t = age / 320f
                    val sx = screenX(fx.fromX + 0.5f)
                    val sy = screenY(fx.fromY + 0.5f)
                    val ex = screenX(fx.toX + 0.5f)
                    val ey = screenY(fx.toY + 0.5f)
                    val px = sx + (ex - sx) * t
                    val py = sy + (ey - sy) * t
                    fill.color = fxColor(fx.kind)
                    val s = tilePx * if (fx.kind == FxKind.ARROW) 0.08f else 0.16f
                    c.drawRect(px - s, py - s, px + s, py + s, fill)
                    fill.alpha = 120
                    val tx = sx + (ex - sx) * max(0f, t - 0.15f)
                    val ty = sy + (ey - sy) * max(0f, t - 0.15f)
                    c.drawRect(tx - s * 0.6f, ty - s * 0.6f, tx + s * 0.6f, ty + s * 0.6f, fill)
                    fill.alpha = 255
                }
                is Fx.Text -> {
                    // Wait for the projectile to land before showing the number.
                    val delayed = age - 220
                    if (delayed < 0) continue
                    val t = delayed / 780f
                    val alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
                    val x = screenX(fx.x + 0.5f)
                    val y = screenY(fx.y + 0.2f) - t * tilePx * 0.8f
                    val size = tilePx * if (fx.kind == FxKind.CRIT) 0.5f else 0.38f
                    text.textSize = size
                    text.textAlign = Paint.Align.CENTER
                    text.color = Color.BLACK
                    text.alpha = alpha
                    c.drawText(fx.text, x + 2, y + 2, text)
                    text.color = fxColor(fx.kind)
                    text.alpha = alpha
                    c.drawText(fx.text, x, y, text)
                    text.alpha = 255
                }
            }
        }
    }

    private fun drawMapOverlay(c: Canvas, g: Game) {
        val pad = height * 0.015f
        val header = if (g.inCombat) "Depth ${g.depth} · Round ${g.round}" else "Depth ${g.depth}"
        val hs = height * 0.04f
        box(c, RectF(0f, 0f, mapW.toFloat(), hs * 2.6f), 0xA0000000.toInt())
        drawText(c, header, pad, hs * 1.1f, hs, cGold)
        drawFitText(c, g.prompt, pad, hs * 2.2f, hs * 0.8f, mapW - pad * 2, cText)

        // Combat log
        val lines = if (logExpanded) 18 else 5
        val ls = height * 0.032f
        val logH = ls * 1.25f * lines + pad
        val logRect = RectF(0f, height - logH, mapW.toFloat(), height.toFloat())
        box(c, logRect, if (logExpanded) 0xE8000000.toInt() else 0x99000000.toInt())
        val visual = g.log.toList().takeLast(lines).flatMap { e ->
            val color = when (e.kind) {
                LogKind.INFO -> 0xFFC8C0D8.toInt()
                LogKind.ROLL -> 0xFF9CD0FF.toInt()
                LogKind.GOOD -> 0xFF8CEB8C.toInt()
                LogKind.BAD -> 0xFFFF8C7A.toInt()
                LogKind.LOOT -> cGold
                LogKind.LEVEL -> 0xFFE0A0FF.toInt()
            }
            wrap(e.text, ls, mapW - pad * 2).map { it to color }
        }.takeLast(lines)
        visual.forEachIndexed { i, (line, color) ->
            drawText(c, line, pad, logRect.top + pad + ls * 1.25f * (i + 1) - ls * 0.2f, ls, color)
        }
        hit(logRect) { logExpanded = !logExpanded }
    }

    // =======================================================================
    // Side panel
    // =======================================================================

    private fun drawPanel(c: Canvas, g: Game) {
        val left = mapW.toFloat()
        val w = width - left
        val h = height.toFloat()
        box(c, RectF(left, 0f, width.toFloat(), h), cPanel)
        val pad = h * 0.012f
        val rowH = h * 0.125f
        var y = pad

        g.party.forEachIndexed { i, hero ->
            val r = RectF(left + pad, y, width - pad, y + rowH - pad)
            val isActive = hero === g.activeHero
            box(c, r, if (hero.dead) 0xFF16121C.toInt() else cPanelLight, if (isActive) cGold else null, 4f)
            val s = rowH - pad * 3
            if (hero.dead) {
                c.drawBitmap(SpriteCache.get(Sprites.GRAVE), null, RectF(r.left + pad, r.top + pad, r.left + pad + s, r.top + pad + s), pixelPaint)
            } else {
                heroSprite(c, hero, r.left + pad, r.top + pad, s)
            }
            val tx = r.left + pad * 2 + s
            val lead = if (g.phase == Phase.EXPLORING && i == g.leaderIndex) "★ " else ""
            drawFitText(c, lead + hero.name, tx, r.top + rowH * 0.27f, rowH * 0.24f, r.right - tx - rowH * 0.5f,
                if (hero.dead) cDim else cGold)
            drawFitText(c, "L${hero.level} ${hero.race.displayName} ${hero.charClass.displayName}", tx, r.top + rowH * 0.48f,
                rowH * 0.17f, r.right - tx - rowH * 0.5f, cDim)

            val barTop = r.top + rowH * 0.56f
            val barRight = r.right - pad
            val barH = rowH * 0.14f
            fill.color = 0xFF000000.toInt()
            c.drawRect(tx, barTop, barRight, barTop + barH, fill)
            val frac = hero.hp.coerceAtLeast(0).toFloat() / hero.maxHp
            fill.color = if (frac < 0.35f) cHpLow else cHp
            c.drawRect(tx, barTop, tx + (barRight - tx) * frac, barTop + barH, fill)
            val status = when {
                hero.dead -> "DEAD"
                hero.isUnconscious && hero.stable -> "STABLE"
                hero.isUnconscious -> "DYING ✓${hero.deathSuccesses} ✗${hero.deathFailures}"
                else -> "${hero.hp}/${hero.maxHp}"
            }
            drawFitText(c, status, (tx + barRight) / 2, barTop + barH * 0.85f, barH * 0.9f, barRight - tx, Color.WHITE, Paint.Align.CENTER)

            val slots = if (hero.charClass.isCaster) "  Slots ${hero.spellSlots.sum()}" else ""
            drawFitText(c, "AC ${hero.armorClass}  Pot ${hero.potions}$slots", tx, r.bottom - pad * 0.8f, rowH * 0.17f,
                r.right - tx - rowH * 0.45f, cText)

            val info = RectF(r.right - rowH * 0.42f, r.top + pad * 0.5f, r.right - pad * 0.5f, r.top + rowH * 0.38f)
            button(c, info, "i") { sheetHero = hero }
            hit(RectF(r.left, r.top, info.left, r.bottom)) { g.selectHero(i) }
            y += rowH
        }

        // Turn economy and initiative
        val ts = h * 0.03f
        if (g.inCombat) {
            val dot = { on: Boolean -> if (on) "●" else "○" }
            val line = if (g.phase == Phase.PLAYER_TURN)
                "Move ${g.movementLeft}  Action ${dot(g.actionAvailable)}  Bonus ${dot(g.bonusActionAvailable)}"
            else "Enemy turn"
            drawFitText(c, line, left + pad, y + ts, ts, w - pad * 2, cText)
            y += ts * 1.4f
            val iconSize = min(h * 0.055f, (w - pad * 2) / max(1, g.turnOrder.size))
            g.turnOrder.forEachIndexed { i, cr ->
                val ix = left + pad + i * iconSize
                val bmp = when (cr) {
                    is Hero -> SpriteCache.hero(cr.race, cr.charClass)
                    is Monster -> SpriteCache.monster(cr.type)
                    else -> null
                } ?: return@forEachIndexed
                val r = RectF(ix, y, ix + iconSize, y + iconSize)
                if (i == g.turnIndex) box(c, r, 0xFF5A4520.toInt())
                c.drawBitmap(bmp, null, r, pixelPaint)
            }
            y += iconSize + pad
        } else {
            drawFitText(c, "Gold ${g.party.sumOf { it.gold }}   Foes slain ${g.kills}", left + pad, y + ts, ts, w - pad * 2, cDim)
            y += ts * 1.6f
        }

        // Action buttons
        val actions = g.availableActions()
        val cols = 2
        val bh = h * 0.075f
        val bw = (w - pad * 3) / cols
        actions.forEachIndexed { i, a ->
            val bx = left + pad + (i % cols) * (bw + pad)
            val by = y + (i / cols) * (bh + pad)
            if (by + bh > h) return@forEachIndexed
            button(c, RectF(bx, by, bx + bw, by + bh), a.label, a.enabled, a.active) { g.perform(a) }
        }
    }

    // =======================================================================
    // Overlays
    // =======================================================================

    private fun drawSheet(c: Canvas, hero: Hero) {
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = 0xC0000000.toInt()
        c.drawRect(0f, 0f, w, h, fill)
        val r = RectF(w * 0.12f, h * 0.06f, w * 0.88f, h * 0.94f)
        box(c, r, cPanel, cGold, 4f)
        hit(RectF(0f, 0f, w, h)) { sheetHero = null }

        val pad = h * 0.03f
        val s = h * 0.22f
        heroSprite(c, hero, r.left + pad, r.top + pad, s)
        val tx = r.left + pad * 2 + s
        var y = r.top + pad + h * 0.05f
        drawText(c, hero.name, tx, y, h * 0.06f, cGold)
        y += h * 0.05f
        drawText(c, "Level ${hero.level} ${hero.race.displayName} ${hero.charClass.displayName}", tx, y, h * 0.035f, cText)
        y += h * 0.045f
        val next = hero.xpForNextLevel?.let { " / $it" } ?: " (max)"
        drawText(c, "XP ${hero.xp}$next   Proficiency +${hero.proficiency}", tx, y, h * 0.03f, cDim)
        y += h * 0.045f
        drawText(c, "HP ${hero.hp}/${hero.maxHp}   AC ${hero.armorClass}   Speed ${hero.speed * 5} ft   Hit dice ${hero.hitDice}", tx, y, h * 0.03f, cText)

        y = r.top + pad + s + h * 0.06f
        val colW = (r.width() - pad * 2) / 6
        Ability.entries.forEachIndexed { i, a ->
            val ax = r.left + pad + colW * i + colW / 2
            box(c, RectF(ax - colW * 0.45f, y - h * 0.045f, ax + colW * 0.45f, y + h * 0.085f), cPanelLight)
            drawText(c, a.name, ax, y - h * 0.01f, h * 0.028f, cDim, Paint.Align.CENTER)
            drawText(c, "${hero.abilities[a]}", ax, y + h * 0.035f, h * 0.045f, cText, Paint.Align.CENTER)
            val save = formatModifier(hero.saveBonus(a)) + if (a in hero.charClass.savingThrows) "•" else ""
            drawText(c, "${formatModifier(hero.abilities.mod(a))}  sv $save", ax, y + h * 0.07f, h * 0.022f, cDim, Paint.Align.CENTER)
        }
        y += h * 0.14f

        val lh = h * 0.042f
        val ls = h * 0.03f
        val width = r.width() - pad * 2
        val melee = hero.charClass.meleeWeapon
        val bonusText = if (hero.weaponBonus > 0) " (+${hero.weaponBonus})" else ""
        drawFitText(c, "${melee.name}$bonusText: ${formatModifier(hero.attackBonus(melee))} to hit, ${melee.damage}${formatModifier(hero.damageBonus(melee))} ${melee.damageType}",
            r.left + pad, y, ls, width)
        y += lh
        hero.charClass.rangedWeapon?.let { rw ->
            drawFitText(c, "${rw.name}: ${formatModifier(hero.attackBonus(rw))} to hit, ${rw.damage}${formatModifier(hero.damageBonus(rw))}, range ${rw.range * 5} ft",
                r.left + pad, y, ls, width)
            y += lh
        }
        if (hero.charClass.isCaster) {
            drawFitText(c, "Spell attack ${formatModifier(hero.spellAttackBonus)}  ·  Save DC ${hero.spellSaveDc}  ·  Slots ${hero.spellSlots.joinToString("/")}",
                r.left + pad, y, ls, width, cGold)
            y += lh
            for (spell in hero.charClass.spells) {
                val lvl = if (spell.isCantrip) "cantrip" else "level ${spell.level}"
                drawFitText(c, "${spell.displayName} ($lvl): ${spell.description}", r.left + pad, y, ls * 0.9f, width, cText)
                y += lh * 0.9f
            }
        }
        drawFitText(c, "Features: ${hero.charClass.features}", r.left + pad, y, ls * 0.9f, width, cDim)
        y += lh * 0.9f
        drawFitText(c, "Race: ${hero.race.trait}", r.left + pad, y, ls * 0.9f, width, cDim)
        y += lh * 0.9f
        drawFitText(c, "Armor: ${hero.charClass.armor.name}" + if (hero.armorBonus > 0) " +${hero.armorBonus}" else "",
            r.left + pad, y, ls * 0.9f, width, cDim)
        drawText(c, "tap to close", r.right - pad, r.bottom - pad, h * 0.025f, cDim, Paint.Align.RIGHT)
    }

    private fun drawGameOver(c: Canvas, g: Game) {
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = 0xB0000000.toInt()
        c.drawRect(0f, 0f, w, h, fill)
        hit(RectF(0f, 0f, w, h)) {}
        drawText(c, "THE PARTY HAS FALLEN", w / 2, h * 0.36f, h * 0.08f, 0xFFE04040.toInt(), Paint.Align.CENTER)
        drawText(c, "Depth ${g.depth}  ·  ${g.kills} foes slain  ·  ${g.party.sumOf { it.gold }} gold",
            w / 2, h * 0.46f, h * 0.04f, cText, Paint.Align.CENTER)
        drawText(c, g.party.joinToString("   ") { "${it.name} L${it.level}" }, w / 2, h * 0.53f, h * 0.032f, cDim, Paint.Align.CENTER)
        button(c, RectF(w * 0.35f, h * 0.62f, w * 0.65f, h * 0.73f), "New Party", active = true) {
            GameSession.newParty()
            premadeIndex.fill(-1)
            sheetHero = null
            screen = Screen.PARTY
        }
    }
}
