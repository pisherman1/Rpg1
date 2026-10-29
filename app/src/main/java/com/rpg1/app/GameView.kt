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
import com.rpg1.core.Creature
import com.rpg1.core.CharClass
import com.rpg1.core.Dungeon
import com.rpg1.core.Fx
import com.rpg1.core.FxKind
import com.rpg1.core.Game
import com.rpg1.core.Hero
import com.rpg1.core.Item
import com.rpg1.core.ItemSlot
import com.rpg1.core.LevelUpNotice
import com.rpg1.core.ActionId
import com.rpg1.core.ItemKind
import com.rpg1.core.LogKind
import com.rpg1.core.Monster
import com.rpg1.core.Phase
import com.rpg1.core.Premades
import com.rpg1.core.Race
import com.rpg1.core.SpellTarget
import com.rpg1.core.Subclass
import com.rpg1.core.audio.SoundId
import com.rpg1.core.audio.TrackId
import com.rpg1.core.Tile
import com.rpg1.core.art.Sprites
import com.rpg1.core.formatModifier
import com.rpg1.render.WorldAnimator
import com.rpg1.render.WorldFrame
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

        /** Hands the next world frame to the GL renderer (null to show nothing). */
        fun submitWorld(frame: WorldFrame?)
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
    private class Hit(val rect: RectF, val action: () -> Unit, val click: Boolean)
    private val hits = mutableListOf<Hit>()
    private var downX = 0f
    private var downY = 0f

    private var mapW = 0
    private var tilePx = 64

    // --- Timing & effects ---------------------------------------------------
    private var lastTick = 0L
    private var nowMs = 0L
    private var lastFrameMs = 0L

    // --- World rendering ----------------------------------------------------
    private val animator = WorldAnimator(Art.atlas)
    /** Set if the GL renderer fails on this device; the map is then drawn with Canvas. */
    private var canvasWorld = false

    private var logExpanded = false
    private var sheetHero: Hero? = null
    private val premadeIndex = IntArray(4) { -1 }
    private var premadePartyIndex = 0

    private val game: Game? get() = GameSession.game

    // --- Level-up, inventory and spell picker --------------------------------
    private var levelUpHero: Hero? = null
    private val asiDraft = mutableMapOf<Ability, Int>()
    private var inventoryOpen = false
    private var invHeroIndex = 0
    private var invSelectedItem: Item? = null
    private var invSelectedSlot: ItemSlot? = null
    /** Open action sub-menu ("Spells" or an action group) and the hero it belongs to. */
    private var subMenu: String? = null
    private var subMenuFor: Hero? = null

    /** Level-up celebrations waiting to be shown, oldest first. */
    private val levelBanners = ArrayDeque<LevelUpNotice>()
    private var bannerStart = 0L

    /** Overlays that pause the game while open. */
    private val paused get() = menuOpen || levelUpHero != null || inventoryOpen

    // --- Save/load menu -----------------------------------------------------
    private var menuOpen = false
    private var slotInfos: Map<Int, SaveStore.SlotInfo?> = emptyMap()
    private var pendingOverwrite = -1
    private var toastText = ""
    private var toastUntil = 0L
    private val dateFormat = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())

    // Autosave when a level starts, a fight ends or the party falls.
    private var lastDepth = -1
    private var lastPhase: Phase? = null

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
        val dt = if (lastFrameMs == 0L) 0f else (nowMs - lastFrameMs) / 1000f
        lastFrameMs = nowMs
        val g = game
        if (screen == Screen.PLAY && g != null) {
            if (!paused) {
                val interval = when {
                    g.phase == Phase.MONSTER_TURN -> 240L
                    g.inCombat -> 150L
                    else -> 105L
                }
                if (nowMs - lastTick >= interval) {
                    lastTick = nowMs
                    g.tick()
                }
                checkAutosave(g)
            }
            animator.setViewport(mapW, height, tilePx / 16)
            animator.update(g, g.drainFx(), if (paused) 0f else dt)
            host.submitWorld(if (canvasWorld) null else animator.buildFrame(g))
            (g.drainSounds() + animator.drainCues()).forEach { GameAudio.play(it) }
            g.drainLevelUps().forEach {
                if (levelBanners.isEmpty()) bannerStart = nowMs
                levelBanners.addLast(it)
            }
            GameAudio.setMusic(when {
                g.phase == Phase.GAME_OVER -> TrackId.DEFEAT
                g.inCombat -> TrackId.COMBAT
                else -> TrackId.EXPLORE
            })
        } else {
            host.submitWorld(null)
            GameAudio.setMusic(TrackId.TITLE)
        }
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun useCanvasWorld() {
        canvasWorld = true
        toast("Using basic graphics on this device")
    }

    private fun checkAutosave(g: Game) {
        val combatEnded = lastPhase != null && lastPhase != Phase.EXPLORING && g.phase == Phase.EXPLORING
        val fell = g.phase == Phase.GAME_OVER && lastPhase != Phase.GAME_OVER
        if ((lastDepth != -1 && g.depth != lastDepth) || combatEnded || fell) GameSession.autosave()
        lastDepth = g.depth
        lastPhase = g.phase
    }

    private fun toast(message: String) {
        toastText = message
        toastUntil = nowMs + 2500
    }

    private fun openMenu() {
        slotInfos = (listOf(SaveStore.AUTOSAVE) + SaveStore.MANUAL_SLOTS).associateWith { GameSession.store.info(it) }
        pendingOverwrite = -1
        menuOpen = true
    }

    private fun loadSlot(slot: Int) {
        try {
            GameSession.load(slot)
            logExpanded = false
            sheetHero = null
            menuOpen = false
            lastDepth = -1
            lastPhase = null
            screen = Screen.PLAY
            toast("Game loaded")
        } catch (e: Exception) {
            toast("Couldn't load: ${e.message}")
        }
    }

    /** Returns true if the back press was consumed. */
    fun handleBack(): Boolean {
        if (menuOpen) { menuOpen = false; return true }
        if (levelUpHero != null) { levelUpHero = null; return true }
        if (inventoryOpen) { inventoryOpen = false; return true }
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
            if (it.click) GameAudio.play(SoundId.CLICK)
            it.action()
            return
        }
        val g = game ?: return
        if (screen != Screen.PLAY || x >= mapW) return
        g.tap(animator.screenToTileX(x), animator.screenToTileY(y))
    }

    private fun hit(rect: RectF, click: Boolean = false, action: () -> Unit) {
        hits += Hit(RectF(rect), action, click)
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
        // In play the GL world shows through the map area; everything else gets a solid background.
        if (screen == Screen.PLAY && !canvasWorld) {
            fill.color = cBg
            canvas.drawRect(mapW.toFloat(), 0f, width.toFloat(), height.toFloat(), fill)
        } else {
            canvas.drawColor(cBg)
        }
        when (screen) {
            Screen.PARTY -> drawPartyScreen(canvas)
            Screen.PLAY -> game?.let { drawPlay(canvas, it) } ?: run { screen = Screen.PARTY }
        }
        if (menuOpen) drawMenu(canvas)
        if (nowMs < toastUntil) drawToast(canvas)
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
        if (enabled) hit(r, click = true, action = action)
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
        if (slotInfos.isEmpty()) slotInfos = (listOf(SaveStore.AUTOSAVE) + SaveStore.MANUAL_SLOTS).associateWith { GameSession.store.info(it) }
        val w = width.toFloat()
        val h = height.toFloat()
        val builder = GameSession.builder
        val pad = h * 0.025f
        drawText(c, "DUNGEON DELVER", w / 2, h * 0.09f, h * 0.07f, cGold, Paint.Align.CENTER)
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
        val bw = (w - pad * 5) / 4
        fun slot(i: Int) = RectF(pad + i * (bw + pad), by, pad + i * (bw + pad) + bw, by + bh)
        val hasAutosave = slotInfos[SaveStore.AUTOSAVE] != null
        button(c, slot(0), "Continue", enabled = hasAutosave) { loadSlot(SaveStore.AUTOSAVE) }
        button(c, slot(1), "Load Game") { openMenu() }
        if (Premades.parties.isNotEmpty()) {
            val party = Premades.parties[premadePartyIndex % Premades.parties.size]
            button(c, slot(2), "Premade: ${party.name}") {
                builder.applyParty(party)
                premadePartyIndex = (premadePartyIndex + 1) % Premades.parties.size
            }
        }
        button(c, slot(3), "Begin Adventure ▶", active = true) {
            GameSession.startGame()
            logExpanded = false
            lastDepth = -1
            lastPhase = null
            screen = Screen.PLAY
        }
    }

    // =======================================================================
    // Dungeon
    // =======================================================================

    private fun drawPlay(c: Canvas, g: Game) {
        c.save()
        c.clipRect(0f, 0f, mapW.toFloat(), height.toFloat())
        if (canvasWorld) drawCanvasWorld(c, g)
        drawMapMarkers(c, g)
        drawFloatTexts(c)
        drawMapOverlay(c, g)
        c.restore()
        drawPanel(c, g)
        drawLevelBanner(c, g)
        sheetHero?.let { drawSheet(c, it) }
        levelUpHero?.let { drawLevelUp(c, it) }
        if (inventoryOpen) drawInventory(c, g)
        if (g.phase == Phase.GAME_OVER) drawGameOver(c, g)
    }

    private fun tileRect(x: Int, y: Int): RectF {
        val l = animator.toScreenX(x * 16f)
        val t = animator.toScreenY(y * 16f)
        return RectF(l, t, l + tilePx, t + tilePx)
    }

    /** Where a creature is drawn right now (it glides between squares). */
    private fun creatureRect(cr: Creature): RectF {
        val l = animator.toScreenX(animator.displayX(cr))
        val t = animator.toScreenY(animator.displayY(cr))
        return RectF(l, t, l + tilePx, t + tilePx)
    }

    /** UI markers over the world: movement range, selection outlines, health bars. */
    private fun drawMapMarkers(c: Canvas, g: Game) {
        val d = g.dungeon
        if (g.phase == Phase.PLAYER_TURN && g.movementLeft > 0 && g.armedSpell == null && !g.armedRanged && !g.armedPotion) {
            g.activeHero?.takeIf { !it.isUnconscious }?.let { h ->
                fill.color = 0x302E7CF0
                stroke.color = 0x604A90FF
                stroke.strokeWidth = 2f
                for ((p, _) in reachable(g, h)) {
                    val r = tileRect(p.first, p.second)
                    c.drawRect(r, fill)
                    c.drawRect(r.left + 1, r.top + 1, r.right - 1, r.bottom - 1, stroke)
                }
            }
        }
        val pulse = (0.5f + 0.5f * sin(nowMs / 180.0).toFloat())
        val feature = g.armedFeature
        val targeting = g.armedSpell != null || g.armedRanged ||
            feature == ActionId.CHALLENGE || feature == ActionId.HUNTERS_MARK || feature == ActionId.VOLLEY
        if (feature == ActionId.RAISE_DEAD) {
            g.activeHero?.let { h ->
                for (p in g.corpses) if (d.isVisible(p.x, p.y) && maxOf(abs(p.x - h.x), abs(p.y - h.y)) <= 6) {
                    outline(c, tileRect(p.x, p.y), 0xFF7CFF9A.toInt(), pulse)
                }
            }
        }
        val active = g.activeHero
        for (m in g.monsters) {
            if (!d.isVisible(m.x, m.y)) continue
            val r = creatureRect(m)
            drawHpBar(c, r, m.hp, m.maxHp)
            if (m === g.current) outline(c, r, if (m.ally) 0xFF7CFF9A.toInt() else 0xFFE04040.toInt(), pulse)
            else if (m.ally) continue
            else if (targeting && active != null && inRangeOf(g, active, m)) outline(c, r, 0xFFFF7040.toInt(), pulse)
            if (m.challenger != null) drawText(c, "⚔", r.left + tilePx * 0.15f, r.top + tilePx * 0.3f, tilePx * 0.28f, cGold, Paint.Align.CENTER)
            if (m.huntedBy != null) drawText(c, "◎", r.right - tilePx * 0.15f, r.bottom - tilePx * 0.08f, tilePx * 0.28f, 0xFF8AE0FF.toInt(), Paint.Align.CENTER)
        }
        val allyTargeting = g.armedPotion || g.armedSpell?.target == SpellTarget.ALLY || g.armedFeature == ActionId.LAY_ON_HANDS
        for (h in g.livingHeroes) {
            val r = creatureRect(h)
            drawHpBar(c, r, h.hp, h.maxHp)
            if (h === active) outline(c, r, cGold, pulse)
            else if (allyTargeting) outline(c, r, 0xFF50E070.toInt(), pulse)
            if (g.phase == Phase.EXPLORING && h === g.leader) {
                drawText(c, "★", r.centerX(), r.top - tilePx * 0.02f, tilePx * 0.3f, cGold, Paint.Align.CENTER)
            }
        }
    }

    /** Basic Canvas rendering of the world, used only if OpenGL fails on the device. */
    private fun drawCanvasWorld(c: Canvas, g: Game) {
        val d = g.dungeon
        val left = animator.screenToTileX(0f) - 1
        val top = animator.screenToTileY(0f) - 1
        val right = animator.screenToTileX(mapW.toFloat()) + 1
        val bottom = animator.screenToTileY(height.toFloat()) + 1
        for (y in top..bottom) for (x in left..right) {
            if (!d.isExplored(x, y)) continue
            c.drawBitmap(SpriteCache.get(when (d[x, y]) {
                Tile.WALL -> if (d[x, y + 1].walkable) Sprites.WALL else Sprites.WALL_TOP
                Tile.FLOOR -> Sprites.FLOOR
                Tile.STAIRS -> Sprites.STAIRS
            }), null, tileRect(x, y), pixelPaint)
        }
        for (p in g.corpses) if (d.isExplored(p.x, p.y)) c.drawBitmap(SpriteCache.get(Sprites.BONES), null, tileRect(p.x, p.y), pixelPaint)
        for (p in g.graves) c.drawBitmap(SpriteCache.get(Sprites.GRAVE), null, tileRect(p.x, p.y), pixelPaint)
        for (item in d.items) {
            if (!d.isExplored(item.x, item.y)) continue
            val s = when (item.kind) {
                ItemKind.CHEST -> Sprites.CHEST
                ItemKind.POTION -> Sprites.POTION
                ItemKind.GOLD -> Sprites.GOLD
                ItemKind.TREASURE -> Sprites.TREASURE
            }
            c.drawBitmap(SpriteCache.get(s), null, tileRect(item.x, item.y), pixelPaint)
        }
        fill.color = 0xB0000000.toInt()
        for (y in top..bottom) for (x in left..right) {
            if (d.isExplored(x, y) && !d.isVisible(x, y)) c.drawRect(tileRect(x, y), fill)
        }
        for (m in g.monsters) if (d.isVisible(m.x, m.y)) c.drawBitmap(SpriteCache.monster(m.type), null, creatureRect(m), pixelPaint)
        for (h in g.livingHeroes) {
            val r = creatureRect(h)
            c.save()
            if (h.isUnconscious) c.rotate(90f, r.centerX(), r.centerY())
            heroSprite(c, h, r.left, r.top, tilePx.toFloat())
            c.restore()
        }
    }

    private fun inRangeOf(g: Game, h: Hero, m: Monster): Boolean {
        val range = g.armedSpell?.range ?: when (g.armedFeature) {
            ActionId.CHALLENGE -> 6
            ActionId.HUNTERS_MARK -> 99
            else -> h.rangedWeapon?.range ?: 1
        }
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

    private fun fxColor(kind: FxKind) = 0xFF000000.toInt() or WorldAnimator.colorOf(kind)

    /** Damage numbers and other combat text, rising and fading above the world. */
    private fun drawFloatTexts(c: Canvas) {
        for (ft in animator.floatTexts) {
            if (ft.age < 0f) continue
            val t = (ft.age / 0.9f).coerceIn(0f, 1f)
            val alpha = ((1f - t * t) * 255).toInt().coerceIn(0, 255)
            val x = animator.toScreenX(ft.x)
            val y = animator.toScreenY(ft.y) - t * tilePx * 0.8f
            // Crits pop in larger.
            val pop = if (ft.kind == FxKind.CRIT) 0.5f + 0.25f * (1f - minOf(1f, t * 4f)) else 0.38f
            text.textSize = tilePx * pop
            text.textAlign = Paint.Align.CENTER
            text.color = Color.BLACK
            text.alpha = alpha
            c.drawText(ft.text, x + 3, y + 3, text)
            text.color = fxColor(ft.kind)
            text.alpha = alpha
            c.drawText(ft.text, x, y, text)
            text.alpha = 255
        }
    }

    private fun drawMapOverlay(c: Canvas, g: Game) {
        val pad = height * 0.015f
        val header = if (g.inCombat) "Depth ${g.depth} · Round ${g.round}" else "Depth ${g.depth}"
        val hs = height * 0.04f
        box(c, RectF(0f, 0f, mapW.toFloat(), hs * 2.6f), 0xA0000000.toInt())
        drawText(c, header, pad, hs * 1.1f, hs, cGold)
        val menuW = hs * 4f
        drawFitText(c, g.prompt, pad, hs * 2.2f, hs * 0.8f, mapW - pad * 4 - menuW * 2, cText)
        button(c, RectF(mapW - pad - menuW, pad, mapW - pad, hs * 2.6f - pad), "Menu") { openMenu() }
        val bagLabel = if (g.bag.isEmpty()) "Bag" else "Bag ${g.bag.size}"
        button(c, RectF(mapW - pad * 2 - menuW * 2, pad, mapW - pad * 2 - menuW, hs * 2.6f - pad), bagLabel,
            active = g.bag.isNotEmpty() && g.canChangeEquipment) {
            inventoryOpen = true
            invSelectedItem = null
            invSelectedSlot = null
            invHeroIndex = g.party.indexOfFirst { !it.dead }.coerceAtLeast(0)
        }

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
            drawFitText(c, "L${hero.level} ${hero.race.displayName} ${hero.subclass?.displayName ?: hero.charClass.displayName}", tx, r.top + rowH * 0.48f,
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
            var hitRight = info.left
            if (hero.hasPendingChoices && !hero.dead) {
                val up = RectF(info.left - pad * 0.5f - info.width(), info.top, info.left - pad * 0.5f, info.bottom)
                button(c, up, "▲", active = true) { openLevelUp(hero) }
                hitRight = up.left
            }
            hit(RectF(r.left, r.top, hitRight, r.bottom)) { g.selectHero(i) }
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

        // Action buttons; spells and grouped moves live in sub-menus.
        val actions = g.availableActions()
        val active = g.activeHero
        if (subMenuFor !== active) subMenu = null
        val spells = actions.filter { it.id == ActionId.SPELL }
        val groups = actions.mapNotNull { it.group }.distinct()
        val cols = 2
        class Btn(val label: String, val enabled: Boolean, val active: Boolean, val onTap: () -> Unit)
        val buttons = mutableListOf<Btn>()
        fun close() {
            subMenu = null
        }
        val open = subMenu
        if (open == SPELLS && spells.isNotEmpty()) {
            buttons += Btn("◀ Back", true, false, ::close)
            val slots = active?.spellSlots?.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "-"
            buttons += Btn("Slots $slots", false, false) {}
            for (a in spells) buttons += Btn(a.label, a.enabled, a.active) {
                g.perform(a)
                close()
            }
        } else if (open != null && open in groups) {
            buttons += Btn("◀ Back", true, false, ::close)
            for (a in actions.filter { it.group == open }) buttons += Btn(a.label, a.enabled, a.active) {
                g.perform(a)
                close()
            }
        } else {
            val first = actions.takeWhile { it.id == ActionId.END_TURN || it.id == ActionId.DESCEND }
            first.forEach { a -> buttons += Btn(a.label, a.enabled, a.active) { g.perform(a) } }
            if (spells.isNotEmpty()) {
                val armed = spells.any { it.active }
                buttons += Btn(if (armed) "Spells ✦" else "Spells ▸", spells.any { it.enabled } || armed, armed) {
                    subMenu = SPELLS
                    subMenuFor = active
                }
            }
            actions.drop(first.size).filter { it.id != ActionId.SPELL && it.group == null }.forEach { a ->
                buttons += Btn(a.label, a.enabled, a.active) { g.perform(a) }
            }
            for (group in groups) {
                val items = actions.filter { it.group == group }
                val on = items.any { it.active }
                buttons += Btn("$group ▸", items.any { it.enabled } || on, on) {
                    subMenu = group
                    subMenuFor = active
                }
            }
        }
        // Shrink buttons if there are more than fit.
        val rows = (buttons.size + cols - 1) / cols
        val bh = minOf(h * 0.075f, (h - y - pad) / maxOf(1, rows) - pad)
        val bw = (w - pad * 3) / cols
        buttons.forEachIndexed { i, b ->
            val bx = left + pad + (i % cols) * (bw + pad)
            val by = y + (i / cols) * (bh + pad)
            if (by + bh > h) return@forEachIndexed
            button(c, RectF(bx, by, bx + bw, by + bh), b.label, b.enabled, b.active, b.onTap)
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
        drawText(c, "Level ${hero.level} ${hero.race.displayName} ${hero.charClass.displayName}" +
            (hero.subclass?.let { " · ${it.displayName}" } ?: ""), tx, y, h * 0.035f, cText)
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
        hero.rangedWeapon?.let { rw ->
            drawFitText(c, "${rw.name}: ${formatModifier(hero.attackBonus(rw))} to hit, ${rw.damage}${formatModifier(hero.damageBonus(rw))}, range ${rw.range * 5} ft",
                r.left + pad, y, ls, width)
            y += lh
        }
        if (hero.charClass.isCaster) {
            drawFitText(c, "Spell attack ${formatModifier(hero.spellAttackBonus)}  ·  Save DC ${hero.spellSaveDc}  ·  Slots ${hero.spellSlots.joinToString("/")}",
                r.left + pad, y, ls, width, cGold)
            y += lh
            val known = hero.knownSpells.sortedBy { it.level }.joinToString(", ") {
                if (it.isCantrip) it.displayName else "${it.displayName} (${it.level})"
            }
            drawFitText(c, "Spells: $known", r.left + pad, y, ls * 0.9f, width, cText)
            y += lh * 0.9f
        }
        val gear = hero.equipment.values.joinToString(", ") { it.nameFor(hero) }.ifEmpty { "none" }
        drawFitText(c, "Magic items: $gear", r.left + pad, y, ls * 0.9f, width, 0xFF5ED35E.toInt())
        y += lh * 0.9f
        drawFitText(c, "Features: ${hero.charClass.features}", r.left + pad, y, ls * 0.9f, width, cDim)
        y += lh * 0.9f
        hero.xpForNextLevel?.let { nextXp ->
            val preview = hero.levelGains(hero.level + 1).joinToString(", ") { it.substringBefore(':') }
            drawFitText(c, "Level ${hero.level + 1} at $nextXp XP: +${hero.hpPerLevel} HP, $preview", r.left + pad, y, ls * 0.9f, width,
                0xFF9CD0FF.toInt())
            y += lh * 0.9f
        }
        hero.subclass?.let { sub ->
            val unlocked = sub.features.filter { hero.level >= it.level }.joinToString(", ") { it.title }
            val next = sub.features.firstOrNull { hero.level < it.level }?.let { "  (next: ${it.title} at ${it.level})" } ?: ""
            drawFitText(c, "${sub.displayName}: $unlocked$next", r.left + pad, y, ls * 0.9f, width, cGold)
            y += lh * 0.9f
        }
        drawFitText(c, "Race: ${hero.race.trait}", r.left + pad, y, ls * 0.9f, width, cDim)
        y += lh * 0.9f
        drawFitText(c, "Armor: ${hero.charClass.armor.name}" + if (hero.armorBonus > 0) " +${hero.armorBonus}" else "",
            r.left + pad, y, ls * 0.9f, width, cDim)
        if (hero.hasPendingChoices && !hero.dead) {
            val b = RectF(r.right - pad - w * 0.18f, r.top + pad, r.right - pad, r.top + pad + h * 0.08f)
            button(c, b, "▲ Level up", active = true) {
                sheetHero = null
                openLevelUp(hero)
            }
        }
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
            slotInfos = emptyMap()
            premadeIndex.fill(-1)
            sheetHero = null
            screen = Screen.PARTY
        }
    }

    private fun drawMenu(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val g = game
        val canSave = screen == Screen.PLAY && g != null && g.phase != Phase.GAME_OVER
        fill.color = 0xD0000000.toInt()
        c.drawRect(0f, 0f, w, h, fill)
        hit(RectF(0f, 0f, w, h)) {}
        val r = RectF(w * 0.14f, h * 0.06f, w * 0.86f, h * 0.94f)
        box(c, r, cPanel, cGold, 4f)
        val pad = h * 0.025f
        drawText(c, if (canSave) "GAME MENU" else "LOAD GAME", r.centerX(), r.top + h * 0.08f, h * 0.055f, cGold, Paint.Align.CENTER)

        val rowH = h * 0.13f
        var y = r.top + h * 0.12f
        val bw = r.width() * 0.16f
        for (slot in listOf(SaveStore.AUTOSAVE) + SaveStore.MANUAL_SLOTS) {
            val info = slotInfos[slot]
            val row = RectF(r.left + pad, y, r.right - pad, y + rowH - pad * 0.6f)
            box(c, row, cPanelLight)
            val name = if (slot == SaveStore.AUTOSAVE) "Autosave" else "Slot $slot"
            val textW = row.width() - bw * 2 - pad * 4
            drawText(c, name, row.left + pad, row.top + rowH * 0.34f, rowH * 0.24f, cGold)
            if (info == null) {
                drawText(c, "Empty", row.left + pad, row.top + rowH * 0.68f, rowH * 0.2f, cDim)
            } else {
                drawFitText(c, info.summary, row.left + pad, row.top + rowH * 0.62f, rowH * 0.19f, textW, cText)
                drawText(c, dateFormat.format(java.util.Date(info.savedAt)), row.left + pad, row.top + rowH * 0.84f, rowH * 0.15f, cDim)
            }
            val loadR = RectF(row.right - pad - bw, row.top + pad * 0.6f, row.right - pad, row.bottom - pad * 0.6f)
            button(c, loadR, "Load", enabled = info != null) { loadSlot(slot) }
            if (canSave && slot != SaveStore.AUTOSAVE) {
                val saveR = RectF(loadR.left - pad - bw, loadR.top, loadR.left - pad, loadR.bottom)
                val confirming = pendingOverwrite == slot
                button(c, saveR, if (confirming) "Overwrite?" else "Save", active = confirming) {
                    if (info != null && !confirming) {
                        pendingOverwrite = slot
                    } else {
                        try {
                            GameSession.store.save(slot, g!!)
                            toast("Saved to slot $slot")
                        } catch (e: Exception) {
                            toast("Save failed: ${e.message}")
                        }
                        openMenu()
                    }
                }
            }
            y += rowH
        }

        val by = r.bottom - pad - h * 0.09f
        val half = (r.width() - pad * 3) / 2
        val ty = by - pad - h * 0.08f
        button(c, RectF(r.left + pad, ty, r.left + pad + half, ty + h * 0.08f), "Music: " + if (GameAudio.musicOn) "On" else "Off",
            active = GameAudio.musicOn) { GameAudio.musicOn = !GameAudio.musicOn }
        button(c, RectF(r.right - pad - half, ty, r.right - pad, ty + h * 0.08f), "Sound: " + if (GameAudio.soundOn) "On" else "Off",
            active = GameAudio.soundOn) { GameAudio.soundOn = !GameAudio.soundOn }
        button(c, RectF(r.left + pad, by, r.left + pad + half, by + h * 0.09f), if (canSave) "Resume" else "Back") { menuOpen = false }
        if (screen == Screen.PLAY) {
            button(c, RectF(r.right - pad - half, by, r.right - pad, by + h * 0.09f), "Save & Quit to Title") {
                GameSession.autosave()
                GameSession.newParty()
                slotInfos = emptyMap()
                premadeIndex.fill(-1)
                sheetHero = null
                menuOpen = false
                screen = Screen.PARTY
            }
        }
    }

    private fun drawToast(c: Canvas) {
        val h = height.toFloat()
        val size = h * 0.035f
        text.textSize = size
        val tw = text.measureText(toastText) + size * 2
        val r = RectF((width - tw) / 2, h * 0.42f, (width + tw) / 2, h * 0.42f + size * 2.2f)
        box(c, r, 0xF0201A2C.toInt(), cGold, 3f)
        drawText(c, toastText, r.centerX(), r.centerY() + size * 0.35f, size, cText, Paint.Align.CENTER)
    }

    // =======================================================================
    // Level up
    // =======================================================================

    private fun openLevelUp(hero: Hero) {
        levelUpHero = hero
        asiDraft.clear()
    }

    private fun drawLevelUp(c: Canvas, hero: Hero) {
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = 0xD0000000.toInt()
        c.drawRect(0f, 0f, w, h, fill)
        hit(RectF(0f, 0f, w, h)) {}
        val r = RectF(w * 0.06f, h * 0.05f, w * 0.94f, h * 0.95f)
        box(c, r, cPanel, cGold, 4f)
        val pad = h * 0.025f
        val s = h * 0.13f
        heroSprite(c, hero, r.left + pad, r.top + pad, s)
        drawText(c, "▲ ${hero.name} · Level ${hero.level} ${hero.subclass?.displayName ?: hero.charClass.displayName}", r.left + pad * 2 + s, r.top + pad + s * 0.45f,
            h * 0.05f, cGold)
        drawText(c, "HP ${hero.maxHp}  ·  Proficiency +${hero.proficiency}" +
            if (hero.charClass.isCaster) "  ·  Spell slots ${hero.maxSpellSlots.joinToString("/")}" else "",
            r.left + pad * 2 + s, r.top + pad + s * 0.85f, h * 0.03f, cDim)

        val top = r.top + pad * 2 + s
        val bottom = r.bottom - pad * 2 - h * 0.09f
        if (hero.needsSubclass) {
            drawSubclassChoice(c, hero, RectF(r.left + pad, top, r.right - pad, bottom))
            val bw = w * 0.2f
            button(c, RectF(r.right - pad - bw, r.bottom - pad - h * 0.09f, r.right - pad, r.bottom - pad), "Later") { levelUpHero = null }
            return
        }
        val both = hero.pendingAbilityPoints > 0 && hero.pendingSpells > 0
        val split = if (both) r.left + r.width() * 0.45f else r.right
        if (hero.pendingAbilityPoints > 0) drawAsi(c, hero, RectF(r.left + pad, top, split - pad, bottom))
        if (hero.pendingSpells > 0) drawSpellPicks(c, hero, RectF(if (both) split + pad else r.left + pad, top, r.right - pad, bottom))
        if (!hero.hasPendingChoices) {
            drawText(c, "All choices made!", r.centerX(), (top + bottom) / 2, h * 0.05f, cText, Paint.Align.CENTER)
        }
        val bw = w * 0.2f
        button(c, RectF(r.right - pad - bw, r.bottom - pad - h * 0.09f, r.right - pad, r.bottom - pad),
            if (hero.hasPendingChoices) "Later" else "Done", active = !hero.hasPendingChoices) { levelUpHero = null }
    }

    private fun drawAsi(c: Canvas, hero: Hero, area: RectF) {
        val h = height.toFloat()
        val used = asiDraft.values.sum()
        val budget = minOf(2, hero.pendingAbilityPoints)
        drawText(c, "Ability Score Improvement", area.left, area.top + h * 0.04f, h * 0.038f, cGold)
        drawText(c, "+2 to one score or +1 to two (max 20). Points: ${budget - used}", area.left, area.top + h * 0.08f, h * 0.027f, cDim)
        val rowH = (area.height() - h * 0.22f) / 6
        Ability.entries.forEachIndexed { i, a ->
            val y = area.top + h * 0.11f + i * rowH
            val row = RectF(area.left, y, area.right, y + rowH * 0.85f)
            box(c, row, cPanelLight)
            val base = hero.baseAbilities[a]
            val add = asiDraft[a] ?: 0
            drawText(c, a.label, row.left + h * 0.02f, row.centerY() + rowH * 0.12f, rowH * 0.34f, cText)
            val scoreText = if (add > 0) "$base → ${base + add}" else "$base"
            drawText(c, scoreText, row.left + row.width() * 0.52f, row.centerY() + rowH * 0.12f, rowH * 0.36f,
                if (add > 0) cGold else cText, Paint.Align.CENTER)
            val bs = row.height() * 0.8f
            val plus = RectF(row.right - bs - h * 0.01f, row.centerY() - bs / 2, row.right - h * 0.01f, row.centerY() + bs / 2)
            val minus = RectF(plus.left - bs - h * 0.01f, plus.top, plus.left - h * 0.01f, plus.bottom)
            button(c, plus, "+", enabled = used < budget && base + add < 20) { asiDraft[a] = add + 1 }
            button(c, minus, "−", enabled = add > 0) { if (add <= 1) asiDraft.remove(a) else asiDraft[a] = add - 1 }
        }
        val by = area.bottom - h * 0.09f
        button(c, RectF(area.left, by, area.left + area.width() * 0.48f, area.bottom), "Reset", enabled = used > 0) { asiDraft.clear() }
        button(c, RectF(area.right - area.width() * 0.48f, by, area.right, area.bottom), "Apply", enabled = used > 0, active = used == budget) {
            if (hero.improveAbilities(asiDraft.toMap())) asiDraft.clear()
        }
    }

    private fun drawSpellPicks(c: Canvas, hero: Hero, area: RectF) {
        val h = height.toFloat()
        drawText(c, "Learn a spell (${hero.pendingSpells} to choose)", area.left, area.top + h * 0.04f, h * 0.038f, cGold)
        val options = hero.learnableSpells().sortedWith(compareBy({ it.level }, { it.displayName }))
        val rowH = minOf(h * 0.13f, (area.height() - h * 0.07f) / maxOf(1, options.size))
        options.forEachIndexed { i, spell ->
            val y = area.top + h * 0.07f + i * rowH
            val row = RectF(area.left, y, area.right, y + rowH * 0.9f)
            box(c, row, cPanelLight, 0xFF4E4266.toInt(), 2f)
            val lvl = if (spell.isCantrip) "Cantrip" else "Level ${spell.level}" + if (spell.bonusAction) " · bonus action" else ""
            drawFitText(c, spell.displayName, row.left + h * 0.015f, row.top + rowH * 0.36f, rowH * 0.3f, row.width() * 0.6f, cText)
            drawFitText(c, lvl, row.right - h * 0.015f, row.top + rowH * 0.36f, rowH * 0.22f, row.width() * 0.38f, cDim, Paint.Align.RIGHT)
            drawFitText(c, spell.description, row.left + h * 0.015f, row.top + rowH * 0.72f, rowH * 0.2f, row.width() - h * 0.03f, cDim)
            hit(row) { hero.learnSpell(spell) }
        }
    }

    // =======================================================================
    // Inventory
    // =======================================================================

    private fun rarityColor(item: Item) = item.rarity.color

    private fun drawInventory(c: Canvas, g: Game) {
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = 0xD0000000.toInt()
        c.drawRect(0f, 0f, w, h, fill)
        hit(RectF(0f, 0f, w, h)) {}
        val r = RectF(w * 0.04f, h * 0.04f, w * 0.96f, h * 0.96f)
        box(c, r, cPanel, cGold, 4f)
        val pad = h * 0.02f
        drawText(c, "PARTY GEAR", r.left + pad, r.top + h * 0.06f, h * 0.045f, cGold)
        if (!g.canChangeEquipment) {
            drawText(c, "Gear can't be changed during combat", r.left + w * 0.25f, r.top + h * 0.06f, h * 0.028f, cHpLow)
        }

        // Hero tabs
        val tabW = r.width() * 0.2f
        val tabH = (r.height() - h * 0.1f - pad) / 4
        g.party.forEachIndexed { i, hero ->
            val t = RectF(r.left + pad, r.top + h * 0.09f + i * tabH, r.left + pad + tabW, r.top + h * 0.09f + (i + 1) * tabH - pad)
            box(c, t, if (i == invHeroIndex) cPanelLight else 0xFF16121C.toInt(), if (i == invHeroIndex) cGold else null, 3f)
            val s = t.height() * 0.7f
            heroSprite(c, hero, t.left + pad * 0.5f, t.centerY() - s / 2, s)
            drawFitText(c, hero.name, t.left + s + pad, t.top + t.height() * 0.4f, t.height() * 0.22f, t.width() - s - pad * 1.5f,
                if (hero.dead) cDim else cText)
            drawFitText(c, if (hero.dead) "fallen" else "AC ${hero.armorClass} · HP ${hero.maxHp}", t.left + s + pad,
                t.top + t.height() * 0.72f, t.height() * 0.17f, t.width() - s - pad * 1.5f, cDim)
            hit(t) {
                invHeroIndex = i
                invSelectedSlot = null
            }
        }
        val hero = g.party.getOrNull(invHeroIndex) ?: return

        // Equipment slots
        val colL = r.left + pad * 2 + tabW
        val colW = r.width() * 0.36f
        val slotH = (r.height() - h * 0.1f - pad) / ItemSlot.entries.size
        ItemSlot.entries.forEachIndexed { i, slot ->
            val row = RectF(colL, r.top + h * 0.09f + i * slotH, colL + colW, r.top + h * 0.09f + (i + 1) * slotH - pad * 0.4f)
            val item = hero.item(slot)
            val selected = invSelectedSlot == slot
            box(c, row, cPanelLight, if (selected) cGold else null, 3f)
            drawText(c, slot.label, row.left + pad * 0.6f, row.centerY() + row.height() * 0.13f, row.height() * 0.32f, cDim)
            drawFitText(c, item?.nameFor(hero) ?: "—", row.left + colW * 0.3f, row.centerY() + row.height() * 0.13f,
                row.height() * 0.38f, colW * 0.68f, item?.let { rarityColor(it) } ?: cDim)
            hit(row) {
                invSelectedSlot = if (item != null) slot else null
                invSelectedItem = null
            }
        }

        // Bag and details
        val bagL = colL + colW + pad
        val bagR = r.right - pad
        val detailTop = r.bottom - pad - h * 0.3f
        drawText(c, "Bag (${g.bag.size})", bagL, r.top + h * 0.12f, h * 0.032f, cGold)
        val itemH = h * 0.065f
        val maxRows = ((detailTop - r.top - h * 0.14f) / itemH).toInt()
        if (g.bag.isEmpty()) drawText(c, "Empty. Treasure from chests and monsters lands here.", bagL, r.top + h * 0.18f, h * 0.025f, cDim)
        g.bag.take(maxRows).forEachIndexed { i, item ->
            val row = RectF(bagL, r.top + h * 0.14f + i * itemH, bagR, r.top + h * 0.14f + (i + 1) * itemH - pad * 0.4f)
            box(c, row, cPanelLight, if (invSelectedItem === item) cGold else null, 3f)
            drawFitText(c, item.name, row.left + pad * 0.6f, row.centerY() + row.height() * 0.15f, row.height() * 0.42f,
                row.width() * 0.62f, rarityColor(item))
            drawText(c, item.slot.label, row.right - pad * 0.6f, row.centerY() + row.height() * 0.15f, row.height() * 0.32f, cDim, Paint.Align.RIGHT)
            hit(row) {
                invSelectedItem = item
                invSelectedSlot = null
            }
        }

        val detail = RectF(bagL, detailTop, bagR, r.bottom - pad)
        box(c, detail, 0xFF16121C.toInt(), 0xFF4E4266.toInt(), 2f)
        val shown = invSelectedItem ?: invSelectedSlot?.let { hero.item(it) }
        if (shown != null) {
            drawFitText(c, if (invSelectedItem != null) shown.name else shown.nameFor(hero), detail.left + pad, detail.top + h * 0.05f,
                h * 0.035f, detail.width() - pad * 2, rarityColor(shown))
            drawText(c, "${shown.rarity.label} · ${shown.slot.label}", detail.left + pad, detail.top + h * 0.09f, h * 0.025f, cDim)
            val lines = wrap(shown.description, h * 0.026f, detail.width() - pad * 2)
            lines.take(2).forEachIndexed { i, line -> drawText(c, line, detail.left + pad, detail.top + h * 0.13f + i * h * 0.033f, h * 0.026f, cText) }
            val bw = detail.width() * 0.55f
            val br = RectF(detail.right - pad - bw, detail.bottom - pad - h * 0.07f, detail.right - pad, detail.bottom - pad)
            if (invSelectedItem != null) {
                val usable = shown.usableBy(hero) && !hero.dead
                val label = when {
                    hero.dead -> "${hero.name} has fallen"
                    !shown.usableBy(hero) -> "Casters only"
                    hero.item(shown.slot) != null -> "Swap onto ${hero.name}"
                    else -> "Equip on ${hero.name}"
                }
                button(c, br, label, enabled = usable && g.canChangeEquipment, active = usable) {
                    if (g.equip(hero, shown)) invSelectedItem = null
                }
            } else {
                button(c, br, "Unequip", enabled = g.canChangeEquipment) {
                    invSelectedSlot?.let { g.unequip(hero, it) }
                    invSelectedSlot = null
                }
            }
        } else {
            drawText(c, "Tap an item in the bag or a slot to see it.", detail.left + pad, detail.top + h * 0.06f, h * 0.026f, cDim)
        }
        val close = RectF(r.right - pad - w * 0.12f, r.top + pad, r.right - pad, r.top + pad + h * 0.065f)
        button(c, close, "Close") { inventoryOpen = false }
    }

    private fun drawSubclassChoice(c: Canvas, hero: Hero, area: RectF) {
        val h = height.toFloat()
        drawText(c, "Choose a path for ${hero.name}", area.left, area.top + h * 0.04f, h * 0.04f, cGold)
        val options = Subclass.forClass(hero.charClass)
        val gap = h * 0.025f
        val cardW = (area.width() - gap * (options.size - 1)) / options.size
        val top = area.top + h * 0.07f
        options.forEachIndexed { i, sub ->
            val card = RectF(area.left + i * (cardW + gap), top, area.left + i * (cardW + gap) + cardW, area.bottom)
            box(c, card, cPanelLight, 0xFF4E4266.toInt(), 3f)
            val p = h * 0.02f
            var y = card.top + h * 0.055f
            drawText(c, sub.displayName, card.left + p, y, h * 0.045f, cGold)
            y += h * 0.015f
            val ls = h * 0.024f
            for (line in wrap(sub.lore, ls, card.width() - p * 2)) {
                y += ls * 1.25f
                drawText(c, line, card.left + p, y, ls, cDim)
            }
            y += ls * 0.8f
            for (f in sub.features) {
                y += ls * 1.35f
                if (y > card.bottom - h * 0.12f) break
                drawText(c, "${f.title} (lvl ${f.level})", card.left + p, y, ls * 1.05f, if (hero.level >= f.level) cText else cDim)
                for (line in wrap(f.text, ls * 0.92f, card.width() - p * 3).take(3)) {
                    y += ls * 1.15f
                    if (y > card.bottom - h * 0.12f) break
                    drawText(c, line, card.left + p * 2, y, ls * 0.92f, cDim)
                }
            }
            val b = RectF(card.left + p, card.bottom - p - h * 0.08f, card.right - p, card.bottom - p)
            button(c, b, "Become a ${sub.displayName}", active = true) { hero.chooseSubclass(sub) }
        }
    }

    // =======================================================================
    // Level-up celebration
    // =======================================================================

    private fun drawLevelBanner(c: Canvas, g: Game) {
        val notice = levelBanners.firstOrNull() ?: return
        val hero = g.party.getOrNull(notice.heroIndex) ?: return levelBanners.clear()
        val duration = if (levelBanners.size > 1) 2600L else 3600L
        val age = nowMs - bannerStart
        if (paused) bannerStart = nowMs - minOf(age, 400L)
        if (age > duration) {
            levelBanners.removeFirst()
            bannerStart = nowMs
            return
        }
        val h = height.toFloat()
        val fadeIn = (age / 250f).coerceIn(0f, 1f)
        val fadeOut = ((duration - age) / 350f).coerceIn(0f, 1f)
        val alpha = minOf(fadeIn, fadeOut)
        val rise = (1f - fadeIn) * h * 0.05f
        val bw = mapW * 0.62f
        val lines = notice.gains.take(5)
        val bh = h * (0.26f + 0.045f * lines.size)
        val r = RectF(mapW / 2f - bw / 2, h * 0.16f + rise, mapW / 2f + bw / 2, h * 0.16f + rise + bh)

        // Glowing gold frame that pulses as it appears.
        val glow = 0.5f + 0.5f * sin(age / 120.0).toFloat()
        fill.color = 0xE0140F1E.toInt()
        fill.alpha = (225 * alpha).toInt()
        c.drawRect(r, fill)
        fill.alpha = 255
        stroke.color = cGold
        for (k in 0..2) {
            stroke.strokeWidth = 4f + k * 3f
            stroke.alpha = ((150 - k * 45) * alpha * (0.6f + 0.4f * glow)).toInt()
            c.drawRect(r.left - k * 4, r.top - k * 4, r.right + k * 4, r.bottom + k * 4, stroke)
        }
        stroke.alpha = 255
        hit(r) { levelBanners.removeFirst(); bannerStart = nowMs }

        val pad = h * 0.025f
        val s = h * 0.17f
        val bob = sin(age / 160.0).toFloat() * h * 0.006f
        if (alpha > 0.05f) heroSprite(c, hero, r.left + pad, r.top + pad + bob, s)
        val tx = r.left + pad * 2 + s
        val pop = 1f + 0.25f * (1f - minOf(1f, age / 300f))
        fun fade(color: Int) = (color and 0xFFFFFF) or ((255 * alpha).toInt().coerceIn(0, 255) shl 24)
        drawText(c, "LEVEL ${notice.level}!", tx, r.top + pad + h * 0.075f, h * 0.075f * pop, fade(cGold))
        drawText(c, "${hero.name} the ${hero.subclass?.displayName ?: hero.charClass.displayName}", tx, r.top + pad + h * 0.125f,
            h * 0.035f, fade(cText))
        drawText(c, "+${notice.hpGain} max HP", tx, r.top + pad + h * 0.17f, h * 0.032f, fade(cHp))
        var y = r.top + pad + s + h * 0.05f
        for (line in lines) {
            drawFitText(c, "✦ $line", r.left + pad, y, h * 0.03f, bw - pad * 2, fade(0xFFE8DCA8.toInt()))
            y += h * 0.045f
        }
        if (hero.hasPendingChoices && !hero.dead) {
            drawText(c, "Tap ▲ on ${hero.name}'s portrait to choose", r.centerX(), r.bottom - pad * 0.8f, h * 0.026f, fade(cDim), Paint.Align.CENTER)
        }
    }

    companion object {
        private const val SPELLS = "Spells"
    }
}
