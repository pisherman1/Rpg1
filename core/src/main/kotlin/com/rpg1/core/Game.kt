package com.rpg1.core

import com.rpg1.core.audio.SoundId

enum class LogKind { INFO, ROLL, GOOD, BAD, LOOT, LEVEL }

data class LogEntry(val text: String, val kind: LogKind)

enum class FxKind { DAMAGE, HEAL, MISS, CRIT, FIRE, RADIANT, FORCE, ARROW, INFO, LOOT, LIGHTNING, NECROTIC }

/** Visual effects for the UI to animate; the game only records them. */
sealed class Fx {
    data class Text(val x: Int, val y: Int, val text: String, val kind: FxKind) : Fx()
    data class Bolt(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val kind: FxKind) : Fx()
}

enum class Phase { EXPLORING, PLAYER_TURN, MONSTER_TURN, GAME_OVER }

enum class ActionId {
    END_TURN, DASH, DODGE, DISENGAGE, POTION, SECOND_WIND, ACTION_SURGE, AIM,
    RANGED, SPELL, SHORT_REST, LONG_REST, DESCEND,
    // Subclass features
    SMITE, LAY_ON_HANDS, CHALLENGE, ENVENOM, HUNTERS_MARK, VOLLEY, RAISE_DEAD,
    PRESERVE_LIFE, DESTRUCTIVE_WRATH, OVERCHANNEL,
}

data class GameAction(
    val id: ActionId,
    val label: String,
    val enabled: Boolean = true,
    /** Currently armed / toggled on. */
    val active: Boolean = false,
    val spell: Spell? = null,
    /** Actions with a group are tucked into a sub-menu of that name. */
    val group: String? = null,
)

/**
 * The whole game state for a party of heroes. The UI calls [tap], [selectHero] and [perform]
 * for input and [tick] on a timer; everything else is read-only state for rendering.
 */
class Game private constructor(val party: List<Hero>, val dice: Dice, fresh: Boolean) {
    constructor(party: List<Hero>, dice: Dice = Dice()) : this(party, dice, fresh = true)

    var depth = 1
        private set
    lateinit var dungeon: Dungeon
        private set
    val monsters = mutableListOf<Monster>()
    val corpses = mutableListOf<Point>()
    /** Where fallen heroes lie on the current level. */
    val graves = mutableListOf<Point>()
    val log = ArrayDeque<LogEntry>()
    private val pendingFx = mutableListOf<Fx>()

    var phase = Phase.EXPLORING
        private set

    /** Index into [party] of the hero leading the march outside combat. */
    var leaderIndex = 0
        private set
    val leader: Hero get() = party[leaderIndex]
    private val trail = ArrayDeque<Point>()

    // --- Combat state -------------------------------------------------------
    val turnOrder = mutableListOf<Creature>()
    private val initiativeRolls = mutableMapOf<Creature, Int>()
    var turnIndex = 0
        private set
    var round = 0
        private set
    val current: Creature? get() = turnOrder.getOrNull(turnIndex)

    var movementLeft = 0
        private set
    var actionAvailable = false
        private set
    var bonusActionAvailable = false
        private set
    private var sneakAttackUsed = false
    private var aiming = false
    private var disengaged = false
    private var movedThisTurn = false
    private var autoEndTurn = false
    /** Extra Attack: weapon attacks left from the current Attack action. */
    var attacksLeft = 0
        private set
    /** The creature whose turn it was left the order; turnIndex already points at the next one. */
    private var turnHolderRemoved = false

    private var divineStrikeUsed = false
    private var monsterStepsLeft = 0
    private var monsterDone = false

    // --- Targeting & movement ----------------------------------------------
    var armedSpell: Spell? = null
        private set
    var armedRanged = false
        private set
    var armedPotion = false
        private set
    /** A subclass feature waiting for a target tap. */
    var armedFeature: ActionId? = null
        private set
    private val walkPath = ArrayDeque<Point>()
    private var walkTarget: Monster? = null
    private var idleTicks = 0

    var longRestUsed = false
        private set
    var kills = 0
        private set
    private var stairsHintShown = false

    init {
        require(party.isNotEmpty())
        if (fresh) startAdventure()
    }

    private fun startAdventure() {
        log("The party enters the dungeon: " + party.joinToString(", ") {
            "${it.name} the ${it.race.displayName} ${it.charClass.displayName}"
        } + ".", LogKind.LEVEL)
        enterLevel()
    }

    // =======================================================================
    // Party queries
    // =======================================================================

    val livingHeroes: List<Hero> get() = party.filter { !it.dead }
    val consciousHeroes: List<Hero> get() = party.filter { !it.dead && it.hp > 0 }

    /** The hero the action buttons apply to: whoever's turn it is, or the leader while exploring. */
    val activeHero: Hero?
        get() = when (phase) {
            Phase.PLAYER_TURN -> current as? Hero
            Phase.EXPLORING -> leader
            else -> null
        }

    fun heroAt(x: Int, y: Int): Hero? = party.firstOrNull { !it.dead && it.x == x && it.y == y }

    fun monsterAt(x: Int, y: Int): Monster? = monsters.firstOrNull { !it.isDead && it.x == x && it.y == y }

    fun isOccupied(x: Int, y: Int) = monsterAt(x, y) != null || heroAt(x, y) != null

    val inCombat get() = phase == Phase.PLAYER_TURN || phase == Phase.MONSTER_TURN

    /** Hostile monsters in the current fight. */
    private fun combatMonsters() = turnOrder.filterIsInstance<Monster>().filter { !it.isDead && !it.ally }

    /** The party's undead minions. */
    val minions: List<Monster> get() = monsters.filter { it.ally && !it.isDead }

    /** A hostile monster on (x, y), if any. */
    fun enemyAt(x: Int, y: Int): Monster? = monsterAt(x, y)?.takeIf { !it.ally }

    private fun onStairs(h: Hero) = h.x == dungeon.stairs.x && h.y == dungeon.stairs.y

    fun drainFx(): List<Fx> = pendingFx.toList().also { pendingFx.clear() }

    /** Sounds for events the renderer can't infer from visual effects. */
    private val pendingSounds = mutableListOf<SoundId>()
    fun drainSounds(): List<SoundId> = pendingSounds.toList().also { pendingSounds.clear() }
    private fun sound(id: SoundId) {
        pendingSounds += id
    }

    val isBusy get() = walkPath.isNotEmpty() || phase == Phase.MONSTER_TURN || autoEndTurn

    fun initiativeOf(c: Creature): Int? = initiativeRolls[c]

    // =======================================================================
    // Level setup
    // =======================================================================

    private fun enterLevel() {
        if (minions.isNotEmpty()) log("Your skeletons crumble to dust as you descend.", LogKind.INFO)
        dungeon = DungeonGenerator.generate(dice)
        monsters.clear()
        corpses.clear()
        graves.clear()
        turnOrder.clear()
        walkPath.clear()
        walkTarget = null
        phase = Phase.EXPLORING
        longRestUsed = false
        stairsHintShown = false
        ensureLivingLeader()
        placeParty()
        populate()
        updateFov()
        log("Depth $depth. Find the stairs down.", LogKind.INFO)
    }

    /** Leader on the start square, everyone else on the nearest free squares. */
    private fun placeParty() {
        val heroes = livingHeroes
        heroes.forEach { it.moveTo(-1, -1) }
        leader.moveTo(dungeon.start.x, dungeon.start.y)
        val spots = squaresNear(dungeon.start, heroes.size)
        heroes.filter { it !== leader }.forEachIndexed { i, h ->
            val p = spots.getOrElse(i) { dungeon.start }
            h.moveTo(p.x, p.y)
        }
        resetTrail()
    }

    /** Free walkable squares around [origin] in breadth-first order (excluding origin). */
    private fun squaresNear(origin: Point, count: Int): List<Point> {
        val found = mutableListOf<Point>()
        val seen = mutableSetOf(origin)
        val queue = ArrayDeque(listOf(origin))
        while (queue.isNotEmpty() && found.size < count) {
            val p = queue.removeFirst()
            for ((dx, dy) in Dungeon.DIRS) {
                val n = Point(p.x + dx, p.y + dy)
                if (n in seen || !dungeon[n.x, n.y].walkable) continue
                seen += n
                queue += n
                if (!isOccupied(n.x, n.y) && n != dungeon.stairs) found += n
            }
        }
        return found
    }

    private fun maxCr() = when {
        depth <= 1 -> 0.25
        depth == 2 -> 0.5
        depth <= 4 -> 1.0
        else -> 2.0
    }

    private fun minCr() = when {
        depth >= 7 -> 0.5
        depth >= 4 -> 0.25
        else -> 0.0
    }

    private fun monsterPool() =
        MonsterType.entries.filter { it.cr <= maxCr() && it.cr >= minCr() && it != MonsterType.OGRE }
            .ifEmpty { listOf(MonsterType.OGRE) }

    /** XP budget for one room's encounter: between an easy and a medium 5e encounter for the party. */
    private fun encounterBudget(): Int {
        val easy = livingHeroes.sumOf { 25 * it.level }
        return easy * (80 + dice.roll(80)) / 100
    }

    private fun populate() {
        val pool = monsterPool()
        for (room in dungeon.rooms.drop(1)) {
            if (dice.chance(1, 2)) spawnEncounter(room, pool)
            val itemRoll = dice.roll(20)
            val kind = when {
                itemRoll <= 7 -> ItemKind.CHEST
                itemRoll <= 10 -> ItemKind.POTION
                itemRoll <= 16 -> ItemKind.GOLD
                else -> null
            }
            if (kind != null) freeSquareIn(room)?.let { dungeon.items += FloorItem(it.x, it.y, kind) }
        }
        if (depth % 4 == 0 || depth >= 9) {
            dungeon.rooms.firstOrNull { it.contains(dungeon.stairs.x, dungeon.stairs.y) }?.let { room ->
                spawnIn(room, MonsterType.OGRE)
                if (depth >= 8) spawnIn(room, MonsterType.OGRE)
            }
        }
    }

    /** Fills [room] with one monster type up to the encounter budget (at least one, at most six). */
    private fun spawnEncounter(room: Room, pool: List<MonsterType>): List<Monster> {
        val budget = encounterBudget()
        val type = dice.pick(pool.filter { it.xp <= budget }.ifEmpty { listOf(pool.minBy { it.xp }) })
        val count = (budget / type.xp).coerceIn(1, 6)
        return (1..count).mapNotNull { spawnIn(room, type) }
    }

    private fun spawnIn(room: Room, type: MonsterType): Monster? {
        val p = freeSquareIn(room) ?: return null
        return Monster(type, dice).also {
            it.moveTo(p.x, p.y)
            monsters += it
        }
    }

    private fun freeSquareIn(room: Room): Point? {
        repeat(30) {
            val x = room.x + dice.roll(room.w) - 1
            val y = room.y + dice.roll(room.h) - 1
            if (dungeon[x, y] == Tile.FLOOR && !isOccupied(x, y) && dungeon.itemAt(x, y) == null &&
                (x != dungeon.start.x || y != dungeon.start.y)
            ) return Point(x, y)
        }
        return null
    }

    // =======================================================================
    // UI helpers
    // =======================================================================

    /** One-line hint about what a tap will do right now. */
    val prompt: String
        get() {
            val h = activeHero
            val spell = armedSpell
            return when {
                phase == Phase.GAME_OVER -> "The party has fallen. Tap New Party to try again."
                spell?.target == SpellTarget.ALLY -> "Tap a hero (or portrait) for ${spell.displayName}"
                spell?.target == SpellTarget.DEAD_ALLY -> "Tap a fallen hero's portrait to revive them"
                armedFeature != null -> featurePrompt(armedFeature!!)
                spell != null -> "Tap a target for ${spell.displayName}"
                armedPotion -> "Tap a hero (or portrait) to give the potion"
                armedRanged -> "Tap a target for the ${h?.rangedWeapon?.name}"
                phase == Phase.MONSTER_TURN -> "${current?.name}'s turn..."
                h != null && phase == Phase.PLAYER_TURN && h.isUnconscious -> "${h.name} is dying! Death saves: " +
                    "${h.deathSuccesses} successes, ${h.deathFailures} failures"
                h != null && phase == Phase.PLAYER_TURN && attacksLeft > 0 -> "${h.name}: Extra Attack! Tap a foe"
                h != null && phase == Phase.PLAYER_TURN -> "${h.name}'s turn: tap to move or attack"
                onStairs(leader) -> "Stairs! Tap Descend to go deeper"
                else -> "Tap to explore. Tap a portrait to change leader"
            }
        }

    private fun featurePrompt(f: ActionId) = when (f) {
        ActionId.LAY_ON_HANDS -> "Tap a hero (or portrait) to Lay on Hands"
        ActionId.CHALLENGE -> "Tap a foe within 30 ft to challenge"
        ActionId.HUNTERS_MARK -> "Tap a foe to mark as your quarry"
        ActionId.VOLLEY -> "Tap the target for your volley"
        ActionId.RAISE_DEAD -> "Tap a corpse within 30 ft to raise it"
        else -> "Tap a target"
    }

    /** True while the active hero can still make a weapon attack this turn. */
    val canAttack get() = phase == Phase.PLAYER_TURN && (actionAvailable || attacksLeft > 0)

    /** Whether [spell] could be cast by [h] right now (ignoring targets). */
    fun canCastNow(h: Hero, spell: Spell): Boolean {
        if (!h.canCast(spell) || h.isUnconscious) return false
        return when (phase) {
            Phase.EXPLORING -> spell.outOfCombatOnly || spell.isHealing && spell.target != SpellTarget.ENEMY ||
                (spell.target == SpellTarget.DEAD_ALLY)
            Phase.PLAYER_TURN -> !spell.outOfCombatOnly && if (spell.bonusAction) bonusActionAvailable else actionAvailable
            else -> false
        } && when (spell.target) {
            SpellTarget.DEAD_ALLY -> party.any { it.dead } && partyGold >= REVIVIFY_COST
            SpellTarget.ALLY, SpellTarget.PARTY -> !spell.isHealing || livingHeroes.any { it.hp < it.maxHp }
            else -> true
        }
    }

    fun availableActions(): List<GameAction> {
        val h = activeHero ?: return emptyList()
        val actions = mutableListOf<GameAction>()
        val cls = h.charClass
        when (phase) {
            Phase.GAME_OVER, Phase.MONSTER_TURN -> {}
            Phase.EXPLORING -> {
                if (onStairs(h)) actions += GameAction(ActionId.DESCEND, "Descend")
                val wounded = livingHeroes.any { it.hp < it.maxHp }
                actions += GameAction(ActionId.POTION, "Potion x${totalPotions()}", totalPotions() > 0 && wounded, armedPotion)
                for (spell in h.knownSpells.filter { it.outOfCombatOnly || (it.isHealing && it.target != SpellTarget.ENEMY) }) {
                    actions += GameAction(ActionId.SPELL, spellLabel(h, spell), canCastNow(h, spell), armedSpell == spell, spell)
                }
                actions += featureActions(h)
                val needsRest = livingHeroes.any { it.hp < it.maxHp || !it.secondWindAvailable || !it.actionSurgeAvailable }
                actions += GameAction(ActionId.SHORT_REST, "Short Rest", needsRest)
                actions += GameAction(ActionId.LONG_REST, "Long Rest", !longRestUsed)
            }
            Phase.PLAYER_TURN -> {
                val mine = !h.isUnconscious
                val bonusCunning = cls == CharClass.ROGUE && h.level >= 2 && bonusActionAvailable
                actions += GameAction(ActionId.END_TURN, "End Turn", !autoEndTurn)
                for (spell in h.knownSpells.filter { !it.outOfCombatOnly }) {
                    actions += GameAction(ActionId.SPELL, spellLabel(h, spell), mine && canCastNow(h, spell), armedSpell == spell, spell)
                }
                h.rangedWeapon?.let {
                    actions += GameAction(ActionId.RANGED, it.name.substringAfterLast(' '), mine && canAttack, armedRanged)
                }
                if (mine) actions += featureActions(h)
                if (cls == CharClass.FIGHTER) {
                    actions += GameAction(ActionId.SECOND_WIND, "2nd Wind", mine && bonusActionAvailable && h.secondWindAvailable)
                    if (h.level >= 2) {
                        actions += GameAction(ActionId.ACTION_SURGE, "Surge", mine && !actionAvailable && h.actionSurgeAvailable)
                    }
                }
                if (cls == CharClass.ROGUE) {
                    actions += GameAction(ActionId.AIM, "Aim", mine && bonusActionAvailable && !movedThisTurn && !aiming, aiming)
                }
                actions += GameAction(ActionId.DASH, if (bonusCunning) "Dash (B)" else "Dash", mine && (actionAvailable || bonusCunning),
                    group = MOVES)
                actions += GameAction(ActionId.DISENGAGE, if (bonusCunning) "Disengage(B)" else "Disengage",
                    mine && !disengaged && (actionAvailable || bonusCunning), disengaged, group = MOVES)
                actions += GameAction(ActionId.DODGE, "Dodge", mine && actionAvailable, group = MOVES)
                actions += GameAction(ActionId.POTION, "Potion x${h.potions}", mine && actionAvailable && h.potions > 0, armedPotion)
            }
        }
        return actions
    }

    /** Buttons for the active hero's subclass features. */
    private fun featureActions(h: Hero): List<GameAction> {
        val list = mutableListOf<GameAction>()
        val fight = phase == Phase.PLAYER_TURN
        val wounded = livingHeroes.any { it.hp < it.maxHp }
        if (h.has(Feature.DIVINE_SMITE) && fight) {
            list += GameAction(ActionId.SMITE, "Smite ${h.uses(Resource.SMITE)}", h.uses(Resource.SMITE) > 0, h.smiteReady)
        }
        if (h.has(Feature.LAY_ON_HANDS)) {
            list += GameAction(ActionId.LAY_ON_HANDS, "Lay Hands ${h.uses(Resource.LAY_ON_HANDS)}",
                h.uses(Resource.LAY_ON_HANDS) > 0 && wounded && (!fight || actionAvailable), armedFeature == ActionId.LAY_ON_HANDS)
        }
        if (h.has(Feature.CHALLENGE) && fight) {
            list += GameAction(ActionId.CHALLENGE, "Challenge (B)", bonusActionAvailable, armedFeature == ActionId.CHALLENGE)
        }
        if (h.has(Feature.ENVENOM) && fight) {
            list += GameAction(ActionId.ENVENOM, "Envenom ${h.uses(Resource.ENVENOM)}",
                bonusActionAvailable && !h.envenomed && h.uses(Resource.ENVENOM) > 0, h.envenomed)
        }
        if (h.has(Feature.HUNTERS_MARK) && fight) {
            list += GameAction(ActionId.HUNTERS_MARK, "Hunt Mark (B)", bonusActionAvailable, armedFeature == ActionId.HUNTERS_MARK)
        }
        if (h.has(Feature.VOLLEY) && fight) {
            list += GameAction(ActionId.VOLLEY, "Volley", actionAvailable, armedFeature == ActionId.VOLLEY)
        }
        if (h.has(Feature.RAISE_DEAD)) {
            val canRaise = minionsOf(h) < maxMinions(h) && h.lowestAvailableSlot(1) != null && corpses.any { dungeon.isVisible(it.x, it.y) }
            list += GameAction(ActionId.RAISE_DEAD, "Raise Dead", canRaise && (!fight || actionAvailable), armedFeature == ActionId.RAISE_DEAD)
        }
        if (h.has(Feature.PRESERVE_LIFE)) {
            list += GameAction(ActionId.PRESERVE_LIFE, "Preserve Life",
                h.uses(Resource.PRESERVE_LIFE) > 0 && wounded && (!fight || actionAvailable))
        }
        if (h.has(Feature.DESTRUCTIVE_WRATH) && fight) {
            list += GameAction(ActionId.DESTRUCTIVE_WRATH, "Storm Wrath", h.uses(Resource.DESTRUCTIVE_WRATH) > 0, h.wrathReady)
        }
        if (h.has(Feature.OVERCHANNEL) && fight) {
            list += GameAction(ActionId.OVERCHANNEL, "Overchannel", h.uses(Resource.OVERCHANNEL) > 0, h.overchannelReady)
        }
        return list
    }

    private fun maxMinions(h: Hero) = if (h.has(Feature.UNDEAD_THRALLS)) 2 else 1
    private fun minionsOf(h: Hero) = minions.count { it.master === h }

    private fun spellLabel(h: Hero, spell: Spell): String = when {
        spell.isCantrip -> spell.shortName
        spell.bonusAction -> "${spell.shortName} (B)"
        else -> spell.shortName
    }

    private fun totalPotions() = livingHeroes.sumOf { it.potions }

    val partyGold get() = party.sumOf { it.gold }

    private fun disarm() {
        armedFeature = null
        armedSpell = null
        armedRanged = false
        armedPotion = false
    }

    // =======================================================================
    // Player input
    // =======================================================================

    fun perform(action: GameAction) {
        if (!action.enabled || autoEndTurn) return
        val h = activeHero ?: return
        when (action.id) {
            ActionId.END_TURN -> endHeroTurn()
            ActionId.DESCEND -> descend()
            ActionId.SHORT_REST -> shortRest()
            ActionId.LONG_REST -> longRest()
            ActionId.DASH -> dash(h)
            ActionId.DODGE -> dodge(h)
            ActionId.DISENGAGE -> disengage(h)
            ActionId.SECOND_WIND -> secondWind(h)
            ActionId.ACTION_SURGE -> actionSurge(h)
            ActionId.AIM -> aim(h)
            ActionId.POTION -> {
                val wasArmed = armedPotion
                disarm()
                armedPotion = !wasArmed
            }
            ActionId.RANGED -> {
                val wasArmed = armedRanged
                disarm()
                armedRanged = !wasArmed
            }
            ActionId.SMITE -> h.smiteReady = !h.smiteReady
            ActionId.ENVENOM -> if (bonusActionAvailable && h.spend(Resource.ENVENOM)) {
                bonusActionAvailable = false
                h.envenomed = true
                log("${h.name} coats their blade in poison.", LogKind.INFO)
            }
            ActionId.DESTRUCTIVE_WRATH -> h.wrathReady = !h.wrathReady
            ActionId.OVERCHANNEL -> h.overchannelReady = !h.overchannelReady
            ActionId.PRESERVE_LIFE -> preserveLife(h)
            ActionId.LAY_ON_HANDS, ActionId.CHALLENGE, ActionId.HUNTERS_MARK, ActionId.VOLLEY, ActionId.RAISE_DEAD -> {
                val wasArmed = armedFeature == action.id
                disarm()
                armedFeature = if (wasArmed) null else action.id
            }
            ActionId.SPELL -> {
                val spell = action.spell ?: return
                val wasArmed = armedSpell == spell
                disarm()
                when (spell.target) {
                    SpellTarget.ADJACENT_AREA, SpellTarget.PARTY -> castSpell(h, spell, null, null)
                    else -> armedSpell = if (wasArmed) null else spell
                }
            }
        }
    }

    /** Player tapped a hero's portrait (or the hero on the map). */
    fun selectHero(index: Int) {
        val target = party.getOrNull(index) ?: return
        if (phase == Phase.GAME_OVER || phase == Phase.MONSTER_TURN || autoEndTurn) return
        val h = activeHero ?: return
        val spell = armedSpell
        if (target.dead) {
            if (spell?.target == SpellTarget.DEAD_ALLY) {
                armedSpell = null
                castSpell(h, spell, null, target)
            }
            return
        }
        when {
            armedPotion -> {
                armedPotion = false
                givePotion(h, target)
            }
            spell?.target == SpellTarget.ALLY -> {
                armedSpell = null
                castSpell(h, spell, null, target)
            }
            armedFeature == ActionId.LAY_ON_HANDS -> {
                armedFeature = null
                layOnHands(h, target)
            }
            phase == Phase.EXPLORING && index != leaderIndex && !target.isUnconscious -> {
                leaderIndex = index
                walkPath.clear()
                resetTrail()
                log("${target.name} takes the lead.", LogKind.INFO)
            }
        }
    }

    /** Player tapped map square (x, y). */
    fun tap(x: Int, y: Int) {
        if (phase == Phase.GAME_OVER || phase == Phase.MONSTER_TURN || autoEndTurn) return
        val h = activeHero ?: return
        if (h.isUnconscious) return

        heroAt(x, y)?.let { ally ->
            if (armedPotion || armedSpell?.target == SpellTarget.ALLY || armedFeature == ActionId.LAY_ON_HANDS ||
                (phase == Phase.EXPLORING && ally !== h)) {
                selectHero(party.indexOf(ally))
                return
            }
        }

        val target = enemyAt(x, y)?.takeIf { dungeon.isVisible(x, y) }
        armedFeature?.let { feature ->
            armedFeature = null
            useTargetedFeature(h, feature, x, y, target)
            return
        }
        armedSpell?.let { spell ->
            disarm()
            if (target != null && (spell.target == SpellTarget.ENEMY || spell.target == SpellTarget.ENEMY_AREA)) {
                castSpell(h, spell, target, null)
            } else {
                log("Cancelled ${spell.displayName}.", LogKind.INFO)
            }
            return
        }
        if (armedRanged || armedPotion) {
            val ranged = armedRanged
            disarm()
            if (ranged && target != null) rangedAttack(h, target) else log("Cancelled.", LogKind.INFO)
            return
        }

        if (target != null) {
            if (h.isAdjacentTo(target)) {
                meleeAttack(h, target)
                return
            }
            if (phase == Phase.PLAYER_TURN && canAttack && tryDefaultRangedAction(h, target)) return
            val path = dungeon.findPath(h.x, h.y, x, y, ::isBlockedForHero, stopAdjacent = true) ?: return
            queueWalk(path, target)
            return
        }

        if (!dungeon.isExplored(x, y) || !dungeon[x, y].walkable) return
        val path = dungeon.findPath(h.x, h.y, x, y, ::isBlockedForHero) ?: return
        queueWalk(path, null)
    }

    // =======================================================================
    // Inventory
    // =======================================================================

    /** Shared party bag of unequipped magic items. */
    val bag = mutableListOf<Item>()

    /** Gear can only be changed outside combat. */
    val canChangeEquipment get() = phase == Phase.EXPLORING

    fun equip(hero: Hero, item: Item): Boolean {
        if (!canChangeEquipment || hero.dead || item !in bag || !item.usableBy(hero)) return false
        bag.remove(item)
        hero.equip(item)?.let { bag += it }
        log("${hero.name} equips ${item.nameFor(hero)}.", LogKind.LOOT)
        return true
    }

    fun unequip(hero: Hero, slot: ItemSlot): Boolean {
        if (!canChangeEquipment) return false
        val item = hero.unequip(slot) ?: return false
        bag += item
        return true
    }

    private fun queueWalk(path: List<Point>, target: Monster?) {
        walkPath.clear()
        walkTarget = null
        if (path.isEmpty()) return
        if (phase == Phase.PLAYER_TURN && movementLeft <= 0) {
            log("No movement left. Dash, or tap End Turn.", LogKind.INFO)
            return
        }
        if (phase == Phase.PLAYER_TURN) {
            // Walk as far as movement allows, never stopping on an ally's square.
            val reachable = path.take(movementLeft).toMutableList()
            while (reachable.isNotEmpty() && isOccupied(reachable.last().x, reachable.last().y)) reachable.removeLast()
            if (reachable.isEmpty()) {
                log("An ally is in the way.", LogKind.INFO)
                return
            }
            walkPath.addAll(reachable)
        } else {
            walkPath.addAll(path)
        }
        walkTarget = target
    }

    /** Heroes may path through allies (but not monsters or unexplored squares). */
    private fun isBlockedForHero(x: Int, y: Int) = enemyAt(x, y) != null || !dungeon.isExplored(x, y)

    /**
     * Tapping a distant foe: rogues shoot, casters use their attack cantrip, and fighters
     * shoot only when they can't reach melee this turn.
     */
    private fun tryDefaultRangedAction(h: Hero, target: Monster): Boolean {
        val cls = h.charClass
        val canCloseIn = (dungeon.findPath(h.x, h.y, target.x, target.y, ::isBlockedForHero, stopAdjacent = true)?.size
            ?: Int.MAX_VALUE) <= movementLeft
        if (cls == CharClass.ROGUE || (h.rangedWeapon != null && !canCloseIn)) {
            val bow = h.rangedWeapon!!
            if (h.distanceTo(target) <= bow.range && hasLos(h, target)) {
                rangedAttack(h, target)
                return true
            }
        }
        val cantrip = h.knownSpells.firstOrNull { it.isCantrip && it.target == SpellTarget.ENEMY } ?: return false
        if (!actionAvailable) return false
        if (h.distanceTo(target) <= cantrip.range && hasLos(h, target)) {
            castSpell(h, cantrip, target, null)
            return true
        }
        return false
    }

    private fun hasLos(a: Creature, b: Creature) = dungeon.hasLineOfSight(a.x, a.y, b.x, b.y)

    // =======================================================================
    // Time
    // =======================================================================

    /** Advances one animation step. Returns true if anything changed. */
    fun tick(): Boolean = when (phase) {
        Phase.GAME_OVER -> false
        Phase.EXPLORING -> {
            if (walkPath.isNotEmpty()) {
                stepHero(leader)
                true
            } else {
                idleTicks++
                if (idleTicks % 3 == 0) moveHuntersWhileExploring() else false
            }
        }
        Phase.PLAYER_TURN -> when {
            autoEndTurn -> {
                autoEndTurn = false
                advanceTurn()
                true
            }
            walkPath.isNotEmpty() -> {
                (current as? Hero)?.let { stepHero(it) }
                true
            }
            else -> false
        }
        Phase.MONSTER_TURN -> {
            monsterStep()
            true
        }
    }

    private fun stepHero(h: Hero) {
        val next = walkPath.removeFirst()
        val isLast = walkPath.isEmpty()
        val ally = heroAt(next.x, next.y)
        // In combat you may move through an ally's square but not stop in it.
        val minion = monsterAt(next.x, next.y)?.takeIf { it.ally }
        val blocked = enemyAt(next.x, next.y) != null || ((ally != null || minion != null) && (phase == Phase.PLAYER_TURN || minion != null) && isLast)
        if (blocked || !dungeon.canStep(h.x, h.y, next.x, next.y)) {
            walkPath.clear()
            return
        }
        if (phase == Phase.PLAYER_TURN) {
            if (movementLeft <= 0) {
                walkPath.clear()
                log("No movement left.", LogKind.INFO)
                return
            }
            val threats = combatMonsters().filter {
                it.isAdjacentTo(h) && chebyshev(it.x, it.y, next.x, next.y) > 1 && it.reactionAvailable && !it.surprised && !it.asleep
            }
            movementLeft--
            movedThisTurn = true
            h.moveTo(next.x, next.y)
            if (!disengaged) {
                for (m in threats) {
                    if (h.hp <= 0) break
                    m.reactionAvailable = false
                    log("${m.name} makes an opportunity attack on ${h.name}!", LogKind.BAD)
                    monsterAttack(m, m.type.melee, h)
                }
                if (h.hp <= 0 || phase == Phase.GAME_OVER) {
                    walkPath.clear()
                    if (phase == Phase.PLAYER_TURN) endHeroTurn()
                    return
                }
            }
        } else {
            val from = Point(h.x, h.y)
            h.moveTo(next.x, next.y)
            followLeader(from)
            minionsFollow()
        }
        updateFov()
        sound(SoundId.STEP)
        pickUp(h)
        if (phase == Phase.EXPLORING) {
            moveHuntersWhileExploring()
        } else {
            joinNewcomers()
        }
        if (walkPath.isEmpty()) {
            val t = walkTarget
            walkTarget = null
            if (t != null && phase == Phase.PLAYER_TURN && !t.isDead && h.isAdjacentTo(t) && canAttack) {
                meleeAttack(h, t)
            }
            if (phase == Phase.EXPLORING && onStairs(h) && !stairsHintShown) {
                stairsHintShown = true
                log("You found the stairs down.", LogKind.GOOD)
            }
        }
    }

    // --- Marching order ----------------------------------------------------

    private fun followers() = livingHeroes.filter { it !== leader }

    /** The trail holds the square each follower is heading for, nearest first. */
    private fun resetTrail() {
        trail.clear()
        followers().forEach { trail.addLast(Point(it.x, it.y)) }
    }

    /** Followers snake along behind the leader, each taking the square the one ahead just left. */
    private fun followLeader(from: Point) {
        trail.remove(Point(leader.x, leader.y))
        trail.addFirst(from)
        val fs = followers()
        while (trail.size > fs.size) trail.removeLast()
        fs.forEachIndexed { i, f ->
            val goal = trail.getOrNull(i) ?: return@forEachIndexed
            if (f.x == goal.x && f.y == goal.y) return@forEachIndexed
            val step = if (dungeon.canStep(f.x, f.y, goal.x, goal.y)) goal
            else dungeon.findPath(f.x, f.y, goal.x, goal.y, { x, y -> monsterAt(x, y) != null }, maxNodes = 400)?.firstOrNull()
            if (step != null && heroAt(step.x, step.y) == null && monsterAt(step.x, step.y) == null) {
                f.moveTo(step.x, step.y)
                pickUp(f)
            }
        }
    }

    /** Skeleton minions trail after the leader outside combat. */
    private fun minionsFollow() {
        for (m in minions) {
            if (m.distanceTo(leader) <= 2) continue
            val step = dungeon.findPath(m.x, m.y, leader.x, leader.y, { x, y -> enemyAt(x, y) != null }, stopAdjacent = true, maxNodes = 600)
                ?.firstOrNull { !isOccupied(it.x, it.y) } ?: continue
            if (chebyshev(m.x, m.y, step.x, step.y) == 1) m.moveTo(step.x, step.y)
        }
    }

    private fun updateFov() = dungeon.computeFov(consciousHeroes.map { Triple(it.x, it.y, it.race.sightRadius) })

    /** Aware monsters hunt the party between fights; seeing any monster starts combat. */
    private fun moveHuntersWhileExploring(): Boolean {
        var moved = false
        for (m in monsters.filter { it.aware && !it.isDead && !it.ally }) {
            val prey = livingHeroes.minByOrNull { it.distanceTo(m) } ?: break
            val path = dungeon.findPath(m.x, m.y, prey.x, prey.y, { x, y -> isOccupied(x, y) }, stopAdjacent = true, maxNodes = 600)
            path?.firstOrNull()?.let {
                m.moveTo(it.x, it.y)
                moved = true
            }
        }
        return checkForCombat() || moved
    }

    private fun checkForCombat(): Boolean {
        val seen = monsters.filter { !it.isDead && !it.ally && dungeon.isVisible(it.x, it.y) }
        if (seen.isEmpty()) return false
        startCombat(seen)
        return true
    }

    // =======================================================================
    // Combat flow
    // =======================================================================

    private fun startCombat(seen: List<Monster>) {
        walkPath.clear()
        walkTarget = null
        disarm()
        turnOrder.clear()
        initiativeRolls.clear()

        val unaware = seen.filter { !it.aware }
        if (unaware.isNotEmpty()) {
            // A monster is surprised only if it notices no one: compare against the party's worst Stealth.
            val totals = consciousHeroes.map { h ->
                val bonus = h.abilities.mod(Ability.DEX) + if (h.charClass == CharClass.ROGUE) h.proficiency else 0
                h to dice.d20(lucky = h.lucky).natural + bonus
            }
            val worst = totals.minBy { it.second }
            log("Stealth: " + totals.joinToString(", ") { "${it.first.name} ${it.second}" }, LogKind.ROLL)
            val surprised = unaware.filter { worst.second > it.type.passivePerception }
            surprised.forEach { it.surprised = true }
            if (surprised.isNotEmpty()) log("You catch ${names(surprised)} by surprise!", LogKind.GOOD)
            else log("${worst.first.name} is spotted!", LogKind.BAD)
        }
        seen.forEach { it.aware = true }

        log("Combat! ${names(seen)}. Roll initiative!", LogKind.BAD)
        sound(SoundId.COMBAT_START)
        monsters.forEach { it.acted = false }
        val all = livingHeroes + seen + minions
        for (c in all) initiativeRolls[c] = rollInitiative(c)
        turnOrder += all.sortedWith(
            compareByDescending<Creature> { initiativeRolls[it] }
                .thenByDescending { it.initiativeBonus }
                .thenBy { if (it is Hero) 0 else 1 },
        )
        log("Initiative: " + turnOrder.joinToString(", ") { "${it.name} ${initiativeRolls[it]}" }, LogKind.ROLL)
        round = 1
        turnIndex = 0
        startTurn()
    }

    private fun rollInitiative(c: Creature): Int =
        dice.d20(lucky = c is Hero && c.lucky).natural + c.initiativeBonus

    /** Monsters that come into view mid-fight join at the end of the order. */
    private fun joinNewcomers() {
        val newcomers = monsters.filter { !it.isDead && !it.ally && it !in turnOrder && dungeon.isVisible(it.x, it.y) }
        for (m in newcomers) {
            m.aware = true
            initiativeRolls[m] = rollInitiative(m)
            turnOrder += m
            log("${m.name} joins the fight!", LogKind.BAD)
        }
    }

    private fun startTurn() {
        val c = current ?: return
        c.dodging = false
        if (c is Hero) {
            phase = Phase.PLAYER_TURN
            movementLeft = c.speed
            actionAvailable = true
            bonusActionAvailable = true
            sneakAttackUsed = false
            aiming = false
            disengaged = false
            movedThisTurn = false
            attacksLeft = 0
            divineStrikeUsed = false
            c.reactionAvailable = true
            disarm()
            if (c.isUnconscious) {
                deathSave(c)
                updateFov()
                if (phase == Phase.PLAYER_TURN && (c.isUnconscious || c.dead)) autoEndTurn = true
            }
        } else if (c is Monster) {
            phase = Phase.MONSTER_TURN
            c.reactionAvailable = true
            monsterStepsLeft = c.speed
            monsterDone = false
            if (c.surprised) {
                log("${c.name} is surprised and loses its turn.", LogKind.INFO)
                monsterDone = true
            } else if (c.asleep) {
                log("${c.name} is fast asleep.", LogKind.INFO)
                monsterDone = true
            }
        }
    }

    private fun endHeroTurn() {
        if (phase != Phase.PLAYER_TURN) return
        disarm()
        walkPath.clear()
        walkTarget = null
        advanceTurn()
    }

    private fun advanceTurn() {
        if (phase == Phase.GAME_OVER) return
        (current as? Monster)?.let {
            it.surprised = false
            it.acted = true
        }
        if (endCombatIfOver()) return
        if (turnHolderRemoved) turnHolderRemoved = false else turnIndex++
        if (turnIndex >= turnOrder.size) {
            turnIndex = 0
            round++
            if (partyEscaped()) {
                endCombat("You slip away from your pursuers.")
                return
            }
        }
        startTurn()
    }

    private fun partyEscaped(): Boolean = combatMonsters().none { m ->
        dungeon.isVisible(m.x, m.y) || livingHeroes.any { it.distanceTo(m) <= 10 }
    }

    private fun endCombatIfOver(): Boolean {
        if (phase == Phase.GAME_OVER || combatMonsters().isNotEmpty()) return false
        sound(SoundId.VICTORY)
        endCombat("The fight is over.")
        return true
    }

    private fun endCombat(message: String) {
        log(message, LogKind.GOOD)
        turnOrder.clear()
        initiativeRolls.clear()
        phase = Phase.EXPLORING
        autoEndTurn = false
        turnHolderRemoved = false
        walkPath.clear()
        disarm()
        livingHeroes.forEach { it.blessed = false }
        for (h in livingHeroes.filter { it.isUnconscious }) {
            h.heal(1)
            log("The party tends to ${h.name}, who comes to with 1 HP.", LogKind.INFO)
        }
        ensureLivingLeader()
        resetTrail()
        updateFov()
        checkForCombat()
    }

    private fun ensureLivingLeader() {
        if (!leader.dead && !leader.isUnconscious) return
        val i = party.indexOfFirst { !it.dead && !it.isUnconscious }
        if (i >= 0) leaderIndex = i
    }

    private fun monsterStep() {
        val m = current as? Monster
        if (m == null || m.isDead || monsterDone) {
            advanceTurn()
            return
        }
        if (m.ally) {
            allyStep(m)
            return
        }
        val targets: List<Creature> = consciousHeroes + minions
        val ranged = m.type.rangedAttack
        // A challenged monster turns on its challenger whenever it can.
        val challenger = m.challenger?.takeIf { !it.dead && !it.isUnconscious }
        fun pick(list: List<Creature>, key: (Creature) -> Int): Creature =
            list.firstOrNull { it === challenger } ?: list.minBy { key(it) * 100 + dice.roll(99) + if (it is Monster) 5000 else 0 }
        val adjacent = targets.filter { m.isAdjacentTo(it) }
        val shootable = if (ranged == null) emptyList() else targets.filter { m.distanceTo(it) <= ranged.range && hasLos(m, it) }
        when {
            adjacent.isNotEmpty() -> {
                attackCreature(m, m.type.melee, pick(adjacent) { it.hp })
                monsterDone = true
            }
            shootable.isNotEmpty() -> {
                attackCreature(m, ranged!!, pick(shootable) { m.distanceTo(it) })
                monsterDone = true
            }
            monsterStepsLeft > 0 -> {
                val order = (listOfNotNull(challenger) + targets.sortedBy { m.distanceTo(it) }).distinct()
                val step = order.firstNotNullOfOrNull { stepToward(m, it) }
                if (step != null) {
                    m.moveTo(step.x, step.y)
                    monsterStepsLeft--
                } else {
                    // Can't reach anyone standing: finish off a fallen hero within reach, if any.
                    livingHeroes.firstOrNull { it.isUnconscious && m.isAdjacentTo(it) }?.let { monsterAttack(m, m.type.melee, it) }
                    monsterDone = true
                }
            }
            else -> monsterDone = true
        }
        // A finished monster hands over on the next tick, so its attack is visible for a moment.
    }

    private fun stepToward(m: Monster, target: Creature): Point? =
        dungeon.findPath(m.x, m.y, target.x, target.y, { x, y -> isOccupied(x, y) }, stopAdjacent = true, maxNodes = 900)?.firstOrNull()

    /** A necromancer's skeleton: attack the nearest foe, or close in. */
    private fun allyStep(m: Monster) {
        val foes = combatMonsters()
        val ranged = m.type.rangedAttack
        val adjacent = foes.filter { m.isAdjacentTo(it) }
        val shootable = if (ranged == null) emptyList() else foes.filter { m.distanceTo(it) <= ranged.range && hasLos(m, it) }
        when {
            foes.isEmpty() -> monsterDone = true
            adjacent.isNotEmpty() -> {
                attackCreature(m, m.type.melee, adjacent.minBy { it.hp })
                monsterDone = true
            }
            shootable.isNotEmpty() -> {
                attackCreature(m, ranged!!, shootable.minBy { m.distanceTo(it) })
                monsterDone = true
            }
            monsterStepsLeft > 0 -> {
                val step = foes.sortedBy { m.distanceTo(it) }.firstNotNullOfOrNull { stepToward(m, it) }
                if (step == null) monsterDone = true else {
                    m.moveTo(step.x, step.y)
                    monsterStepsLeft--
                }
            }
            else -> monsterDone = true
        }
    }

    private fun attackCreature(m: Monster, attack: MonsterAttack, target: Creature) {
        if (target is Hero) monsterAttack(m, attack, target) else monsterAttackMonster(m, attack, target as Monster)
    }

    /** Skeleton minions against monsters, and monsters against skeletons. */
    private fun monsterAttackMonster(m: Monster, attack: MonsterAttack, target: Monster) {
        if (attack.ranged) pendingFx += Fx.Bolt(m.x, m.y, target.x, target.y, FxKind.ARROW)
        val roll = dice.d20(RollMode.of(target.asleep, target.dodging))
        val total = roll.natural + attack.toHit
        val hit = roll.isCritical || (!roll.isFumble && total >= target.armorClass)
        log("${m.name}${if (m.ally) " (yours)" else ""} ${attack.name} on ${target.name}: ${roll.describe()}${fmt(attack.toHit)} = $total vs AC ${target.armorClass}, " +
            if (roll.isCritical) "CRITICAL HIT!" else if (hit) "hit" else "miss", if (hit == m.ally) LogKind.ROLL else LogKind.INFO)
        if (!hit) {
            pendingFx += Fx.Text(target.x, target.y, "miss", FxKind.MISS)
            return
        }
        val damage = maxOf(1, dice.roll(attack.damage, roll.isCritical) + m.bonusDamage)
        damageMonster(target, damage, roll.isCritical, attack.damageType, "${attack.damage}")
    }

    /** +1 AC from a paladin's Aura of Protection within 10 ft. */
    private fun auraPaladin(target: Hero): Hero? = livingHeroes.firstOrNull {
        it.has(Feature.AURA_OF_PROTECTION) && !it.isUnconscious && it.distanceTo(target) <= 2
    }

    // =======================================================================
    // Attacks & damage
    // =======================================================================

    private fun useAction(): Boolean {
        if (phase != Phase.PLAYER_TURN) return phase == Phase.EXPLORING
        if (!actionAvailable) {
            log("${activeHero?.name} has already used their action. Tap End Turn.", LogKind.INFO)
            return false
        }
        actionAvailable = false
        return true
    }

    /** Spends one attack: the first uses the action, Extra Attack gives more for free. */
    private fun useAttack(h: Hero): Boolean {
        if (phase != Phase.PLAYER_TURN) return false
        if (attacksLeft > 0) {
            attacksLeft--
            return true
        }
        if (!useAction()) return false
        attacksLeft = h.attacksPerAction - 1
        return true
    }

    private fun meleeAttack(h: Hero, target: Monster) {
        if (phase != Phase.PLAYER_TURN) return
        if (!useAttack(h)) return
        weaponAttack(h, target, h.charClass.meleeWeapon)
    }

    private fun rangedAttack(h: Hero, target: Monster) {
        if (phase != Phase.PLAYER_TURN) return
        val weapon = h.rangedWeapon ?: return
        if (h.distanceTo(target) > weapon.range) {
            log("${target.name} is out of range (${weapon.range} squares).", LogKind.INFO)
            return
        }
        if (!hasLos(h, target)) {
            log("No clear shot.", LogKind.INFO)
            return
        }
        if (!useAttack(h)) return
        weaponAttack(h, target, weapon)
    }

    private fun weaponAttack(h: Hero, target: Monster, weapon: Weapon) {
        if (weapon.ranged) pendingFx += Fx.Bolt(h.x, h.y, target.x, target.y, FxKind.ARROW)
        val label = "${h.name}'s " + if (h.weaponBonus > 0) "+${h.weaponBonus} ${weapon.name}" else weapon.name
        val hit = heroAttackRoll(h, target, h.attackBonus(weapon), weapon.ranged, label) ?: return
        var damage = dice.roll(weapon.damage, hit.critical) + h.damageBonus(weapon)
        var detail = "${weapon.damage}${fmt(h.damageBonus(weapon))}"
        // Sneak Attack: advantage, or an ally next to the target (and no disadvantage).
        val allyAdjacent = consciousHeroes.any { it !== h && it.isAdjacentTo(target) }
        if (h.charClass == CharClass.ROGUE && !sneakAttackUsed && hit.roll.mode != RollMode.DISADVANTAGE &&
            (hit.roll.mode == RollMode.ADVANTAGE || allyAdjacent || target.surprised)
        ) {
            sneakAttackUsed = true
            damage += dice.roll(DiceExpr(h.sneakAttackDice, 6), hit.critical)
            detail += " + Sneak Attack ${h.sneakAttackDice}d6"
        }
        if (target.huntedBy === h) {
            damage += dice.roll(d("1d6"), hit.critical)
            detail += " + Hunter's Mark 1d6"
        }
        if (h.envenomed) {
            h.envenomed = false
            damage += dice.roll(d("2d6"), hit.critical)
            detail += " + poison 2d6"
        }
        if (!weapon.ranged && h.smiteReady && h.spend(Resource.SMITE)) {
            h.smiteReady = false
            val n = if (target.type.isUndead) 3 else 2
            damage += dice.roll(DiceExpr(n, 8), hit.critical)
            detail += " + Divine Smite ${n}d8 radiant"
            pendingFx += Fx.Bolt(target.x, target.y - 1, target.x, target.y, FxKind.RADIANT)
        }
        if (h.has(Feature.DIVINE_STRIKE) && !divineStrikeUsed) {
            divineStrikeUsed = true
            val maxed = h.wrathReady && h.spend(Resource.DESTRUCTIVE_WRATH)
            if (maxed) h.wrathReady = false
            damage += if (maxed) 8 * (if (hit.critical) 2 else 1) else dice.roll(d("1d8"), hit.critical)
            detail += " + Divine Strike 1d8 thunder" + if (maxed) " (max)" else ""
        }
        if (h.has(Feature.DEATH_STRIKE) && !target.acted) {
            val dc = 8 + h.abilities.mod(Ability.DEX) + h.proficiency
            val save = dice.d20().natural + target.saveBonus(Ability.CON)
            if (save < dc) {
                damage *= 2
                detail += ", Death Strike doubles it (CON $save vs DC $dc)"
            }
        }
        damageMonster(target, maxOf(1, damage), hit.critical, weapon.damageType, detail)
    }

    private class Hit(val roll: D20Roll, val critical: Boolean)

    /** Rolls a hero's attack and logs it. Returns the hit (with crit flag), or null on a miss. */
    private fun heroAttackRoll(h: Hero, target: Monster, bonus: Int, ranged: Boolean, label: String): Hit? {
        val assassinate = h.has(Feature.ASSASSINATE) && !target.acted
        val advantage = target.marked || aiming || target.asleep || assassinate
        val enemyAdjacent = combatMonsters().any { it.isAdjacentTo(h) && !it.asleep }
        val disadvantage = target.dodging || (ranged && enemyAdjacent)
        val roll = dice.d20(RollMode.of(advantage, disadvantage), h.lucky)
        target.marked = false
        aiming = false
        val bless = if (h.blessed) dice.roll(4) else 0
        val total = roll.natural + bonus + bless
        val hit = roll.isCritical || (!roll.isFumble && total >= target.armorClass)
        // Melee hits on a sleeping foe within reach are critical, as against any helpless creature.
        val critical = hit && (roll.isCritical || (target.asleep && !ranged && h.isAdjacentTo(target)) ||
            (h.has(Feature.ASSASSINATE) && target.surprised))
        val modeNote = when (roll.mode) {
            RollMode.ADVANTAGE -> " (adv)"
            RollMode.DISADVANTAGE -> " (disadv)"
            RollMode.NORMAL -> ""
        }
        val verdict = when {
            critical -> "CRITICAL HIT!"
            hit -> "hit"
            roll.isFumble -> "natural 1, miss"
            else -> "miss"
        }
        val blessNote = if (h.blessed) " +bless $bless" else ""
        log("$label vs ${target.name}: ${roll.describe()}${fmt(bonus)}$blessNote = $total vs AC ${target.armorClass}$modeNote, $verdict",
            if (hit) LogKind.ROLL else LogKind.INFO)
        if (!hit) pendingFx += Fx.Text(target.x, target.y, "miss", FxKind.MISS)
        return if (hit) Hit(roll, critical) else null
    }

    private fun damageMonster(target: Monster, amount: Int, critical: Boolean, type: String, detail: String) {
        if (target.isDead) return
        target.hp -= amount
        if (target.asleep && target.hp > 0) {
            target.asleep = false
            log("${target.name} wakes up!", LogKind.INFO)
        }
        pendingFx += Fx.Text(target.x, target.y, "-$amount", if (critical) FxKind.CRIT else FxKind.DAMAGE)
        log("${target.name} takes $amount $type damage ($detail).", LogKind.GOOD)
        if (target.hp <= 0 && target.type == MonsterType.ZOMBIE && type != "radiant" && !critical) {
            val dc = 5 + amount
            val save = dice.d20().natural + target.saveBonus(Ability.CON)
            if (save >= dc) {
                target.hp = 1
                log("Undead Fortitude! The zombie rolls $save vs DC $dc and refuses to fall.", LogKind.BAD)
                return
            }
        }
        if (target.hp <= 0) killMonster(target)
    }

    private fun removeFromTurnOrder(c: Creature) {
        val idx = turnOrder.indexOf(c)
        if (idx < 0) return
        turnOrder.removeAt(idx)
        if (idx < turnIndex) turnIndex--
    }

    private fun killMonster(m: Monster) {
        m.hp = 0
        removeFromTurnOrder(m)
        monsters.remove(m)
        corpses += Point(m.x, m.y)
        if (m.ally) {
            log("Your ${m.name} collapses into a heap of bones.", LogKind.BAD)
            return
        }
        kills++
        castingHero?.let { caster ->
            if (caster.has(Feature.GRIM_HARVEST) && castingSlot >= 1) {
                val healed = caster.heal(2 * castingSlot)
                if (healed > 0) {
                    pendingFx += Fx.Text(caster.x, caster.y, "+$healed", FxKind.HEAL)
                    log("Grim Harvest: ${caster.name} drinks in the dying life force (+$healed HP).", LogKind.GOOD)
                }
            }
        }
        val share = m.type.xp / livingHeroes.size.coerceAtLeast(1)
        log("${m.name} is slain! +$share XP each", LogKind.GOOD)
        for (h in livingHeroes) {
            h.gainXp(share, dice).forEach {
                log(it, LogKind.LEVEL)
                pendingFx += Fx.Text(h.x, h.y, "LEVEL UP", FxKind.HEAL)
            }
        }
        if (dungeon.itemAt(m.x, m.y) == null && dungeon[m.x, m.y].walkable) {
            val boss = m.type == MonsterType.OGRE
            when {
                boss || dice.roll(100) <= 3 + m.type.cr.toInt() * 3 ->
                    dungeon.items += FloorItem(m.x, m.y, ItemKind.TREASURE, Loot.randomItem(dice, depth, boss))
                dice.roll(12) <= 2 -> dungeon.items += FloorItem(m.x, m.y, ItemKind.POTION)
                dice.roll(12) <= 3 -> dungeon.items += FloorItem(m.x, m.y, ItemKind.GOLD)
            }
        }
        if (phase == Phase.PLAYER_TURN) endCombatIfOver()
    }

    private fun monsterAttack(m: Monster, attack: MonsterAttack, target: Hero) {
        if (attack.ranged) pendingFx += Fx.Bolt(m.x, m.y, target.x, target.y, FxKind.ARROW)
        val advantage = target.isUnconscious
        val challenged = m.challenger?.takeIf { !it.dead && !it.isUnconscious && it !== target }
        val disadvantage = target.dodging || challenged != null
        val roll = dice.d20(RollMode.of(advantage, disadvantage))
        val total = roll.natural + attack.toHit
        val ac = target.armorClass + if (auraPaladin(target) != null) 1 else 0
        var hit = roll.isCritical || (!roll.isFumble && total >= ac)
        val critical = hit && (roll.isCritical || (target.isUnconscious && !attack.ranged))
        log("${m.name} ${attack.name} on ${target.name}: ${roll.describe()}${fmt(attack.toHit)} = $total vs AC $ac" +
            (if (challenged != null) " (challenged, disadv)" else "") + ", " +
            if (critical) "CRITICAL HIT!" else if (hit) "hit" else "miss", if (hit) LogKind.BAD else LogKind.INFO)
        // Knight's Warding Maneuver can turn a hit on an adjacent ally into a miss.
        if (hit && !roll.isCritical) {
            livingHeroes.firstOrNull {
                it !== target && it.has(Feature.WARDING_MANEUVER) && it.reactionAvailable && !it.isUnconscious && it.isAdjacentTo(target)
            }?.let { knight ->
                knight.reactionAvailable = false
                val ward = dice.roll(8)
                if (total < ac + ward) {
                    hit = false
                    log("${knight.name}'s Warding Maneuver adds $ward to ${target.name}'s AC: the blow glances off!", LogKind.GOOD)
                } else {
                    log("${knight.name} tries to ward ${target.name} (+$ward AC) but the blow still lands.", LogKind.INFO)
                }
            }
        }
        if (!hit) {
            pendingFx += Fx.Text(target.x, target.y, "miss", FxKind.MISS)
            return
        }
        var damage = maxOf(1, dice.roll(attack.damage, critical))
        if (target.hasUncannyDodge && target.reactionAvailable && !target.isUnconscious) {
            target.reactionAvailable = false
            damage = maxOf(1, damage / 2)
            log("${target.name} uses Uncanny Dodge to halve the damage!", LogKind.GOOD)
        }
        damageHero(target, damage, critical, "${attack.damageType} from ${m.name}")
        // Tempest cleric: lightning lashes back at melee attackers.
        if (!attack.ranged && !m.isDead && target.has(Feature.WRATH_OF_THE_STORM) && target.reactionAvailable &&
            !target.isUnconscious && !target.dead && target.spend(Resource.WRATH_OF_THE_STORM)
        ) {
            target.reactionAvailable = false
            val maxed = target.wrathReady && target.spend(Resource.DESTRUCTIVE_WRATH)
            if (maxed) target.wrathReady = false
            val dmg = if (maxed) 16 else dice.roll(d("2d8"))
            pendingFx += Fx.Bolt(target.x, target.y, m.x, m.y, FxKind.LIGHTNING)
            log("Wrath of the Storm! Lightning lashes at the ${m.name}${if (maxed) " (Destructive Wrath: maximum)" else ""}.", LogKind.GOOD)
            val saved = monsterSave(target, m, Ability.DEX)
            damageMonster(m, if (saved) dmg / 2 else dmg, false, "lightning", if (saved) "2d8, halved" else "2d8")
        }
    }

    private fun damageHero(h: Hero, amount: Int, critical: Boolean, source: String) {
        pendingFx += Fx.Text(h.x, h.y, "-$amount", if (critical) FxKind.CRIT else FxKind.DAMAGE)
        if (h.isUnconscious) {
            h.stable = false
            h.deathFailures += if (critical) 2 else 1
            log("A blow lands on the fallen ${h.name}! Death save failures: ${h.deathFailures}", LogKind.BAD)
            if (h.deathFailures >= 3) heroDies(h, "${h.name} has died.")
            return
        }
        h.hp -= amount
        log("${h.name} takes $amount $source.", LogKind.BAD)
        if (h.hp <= 0) {
            val overflow = -h.hp
            h.hp = 0
            if (overflow >= h.maxHp) {
                heroDies(h, "Massive damage! ${h.name} is killed outright.")
            } else {
                h.deathSuccesses = 0
                h.deathFailures = 0
                log("${h.name} falls unconscious!", LogKind.BAD)
                sound(SoundId.HERO_DOWN)
                updateFov()
                checkTotalPartyKill()
            }
        }
    }

    private fun deathSave(h: Hero) {
        if (!h.stable && h.hasWoundClosure) {
            h.stable = true
            log("${h.name}'s Periapt of Wound Closure stabilises them.", LogKind.GOOD)
        }
        if (h.stable) {
            log("${h.name} is stable but unconscious.", LogKind.INFO)
            return
        }
        val roll0 = dice.d20(lucky = h.lucky)
        val aura = auraPaladin(h)?.let { maxOf(1, it.abilities.mod(Ability.CHA)) } ?: 0
        if (aura > 0) log("${h.name} draws strength from the Aura of Protection (+$aura).", LogKind.INFO)
        val roll = roll0.copy(natural = if (roll0.natural in 2..19) minOf(19, roll0.natural + aura) else roll0.natural)
        when {
            roll.natural == 20 -> {
                h.heal(1)
                log("${h.name}'s death save: natural 20! Back on their feet with 1 HP!", LogKind.GOOD)
            }
            roll.natural == 1 -> {
                h.deathFailures += 2
                log("${h.name}'s death save: natural 1, two failures (${h.deathFailures}/3).", LogKind.BAD)
            }
            roll.natural >= 10 -> {
                h.deathSuccesses++
                log("${h.name}'s death save: ${roll.natural}, success (${h.deathSuccesses}/3).", LogKind.ROLL)
            }
            else -> {
                h.deathFailures++
                log("${h.name}'s death save: ${roll.natural}, failure (${h.deathFailures}/3).", LogKind.BAD)
            }
        }
        if (h.deathFailures >= 3) {
            heroDies(h, "${h.name} has died.")
        } else if (h.deathSuccesses >= 3) {
            h.stable = true
            log("${h.name} is stable.", LogKind.INFO)
        }
    }

    private fun heroDies(h: Hero, message: String) {
        sound(SoundId.HERO_DIE)
        h.dead = true
        h.hp = 0
        graves += Point(h.x, h.y)
        if (inCombat && current === h) turnHolderRemoved = true
        removeFromTurnOrder(h)
        log(message, LogKind.BAD)
        // Their potions go to the survivors.
        livingHeroes.firstOrNull()?.let { it.potions += h.potions }
        h.potions = 0
        checkTotalPartyKill()
    }

    /** The adventure ends when nobody in the party is left standing. */
    private fun checkTotalPartyKill() {
        if (consciousHeroes.isNotEmpty()) return
        phase = Phase.GAME_OVER
        walkPath.clear()
        autoEndTurn = false
        disarm()
        log("The whole party has fallen.", LogKind.BAD)
        log("Reached depth $depth, ${party.sumOf { it.gold }} gold, $kills foes slain.", LogKind.INFO)
    }

    // =======================================================================
    // Subclass features
    // =======================================================================

    private fun useTargetedFeature(h: Hero, feature: ActionId, x: Int, y: Int, target: Monster?) {
        when (feature) {
            ActionId.CHALLENGE -> {
                if (target == null || h.distanceTo(target) > 6) return log("Challenge a foe you can see within 30 ft.", LogKind.INFO)
                if (!bonusActionAvailable) return
                bonusActionAvailable = false
                monsters.filter { it.challenger === h }.forEach { it.challenger = null }
                target.challenger = h
                pendingFx += Fx.Text(target.x, target.y, "challenged", FxKind.INFO)
                log("${h.name} challenges the ${target.name}: \"Face me!\"", LogKind.GOOD)
            }
            ActionId.HUNTERS_MARK -> {
                if (target == null) return log("Mark a foe you can see.", LogKind.INFO)
                if (!bonusActionAvailable) return
                bonusActionAvailable = false
                monsters.filter { it.huntedBy === h }.forEach { it.huntedBy = null }
                target.huntedBy = h
                pendingFx += Fx.Text(target.x, target.y, "marked", FxKind.INFO)
                log("${h.name} marks the ${target.name} as their quarry.", LogKind.GOOD)
            }
            ActionId.VOLLEY -> {
                val bow = h.rangedWeapon ?: return
                if (target == null || h.distanceTo(target) > bow.range || !hasLos(h, target)) {
                    return log("Volley needs a visible target within ${bow.range * 5} ft.", LogKind.INFO)
                }
                if (!useAction()) return
                val victims = combatMonsters().filter { chebyshev(it.x, it.y, target.x, target.y) <= 1 }
                log("${h.name} looses a volley of ${victims.size} arrows!", LogKind.INFO)
                for (v in victims) if (!v.isDead) weaponAttack(h, v, bow)
            }
            ActionId.RAISE_DEAD -> {
                val corpse = Point(x, y)
                if (corpse !in corpses || !dungeon.isVisible(x, y) || chebyshev(h.x, h.y, x, y) > 6) {
                    return log("Tap a visible corpse within 30 ft.", LogKind.INFO)
                }
                if (minionsOf(h) >= maxMinions(h)) return log("${h.name} can't control another skeleton.", LogKind.INFO)
                val slot = h.lowestAvailableSlot(1) ?: return log("No spell slots left to raise the dead.", LogKind.INFO)
                if (inCombat && !useAction()) return
                h.spendSlot(slot)
                corpses.remove(corpse)
                val skeleton = Monster(MonsterType.SKELETON, dice).apply {
                    ally = true
                    master = h
                    aware = true
                    if (h.has(Feature.UNDEAD_THRALLS)) {
                        extraHp = h.level
                        bonusDamage = h.proficiency
                    }
                    hp = maxHp
                }
                val spot = if (!isOccupied(x, y)) corpse else squaresNear(corpse, 1).firstOrNull() ?: return
                skeleton.moveTo(spot.x, spot.y)
                monsters += skeleton
                if (inCombat) {
                    turnOrder += skeleton
                    initiativeRolls[skeleton] = 0
                }
                pendingFx += Fx.Bolt(h.x, h.y, spot.x, spot.y, FxKind.NECROTIC)
                pendingFx += Fx.Text(spot.x, spot.y, "rise!", FxKind.NECROTIC)
                log("${h.name} speaks a word of death; the bones knit into a skeleton servant (level $slot slot).", LogKind.LEVEL)
            }
            else -> {}
        }
    }

    private fun layOnHands(h: Hero, target: Hero) {
        if (inCombat && h.distanceTo(target) > 1) return log("Lay on Hands needs a touch: move next to ${target.name}.", LogKind.INFO)
        val amount = minOf(h.uses(Resource.LAY_ON_HANDS), target.maxHp - target.hp.coerceAtLeast(0))
        if (amount <= 0) return log("${target.name} doesn't need healing.", LogKind.INFO)
        if (!useAction()) return
        h.spend(Resource.LAY_ON_HANDS, amount)
        val healed = target.heal(amount)
        pendingFx += Fx.Text(target.x, target.y, "+$healed", FxKind.HEAL)
        log("${h.name} lays hands on ${target.name}: +$healed HP (${h.uses(Resource.LAY_ON_HANDS)} left in the pool).", LogKind.GOOD)
        updateFov()
    }

    private fun preserveLife(h: Hero) {
        var pool = 5 * h.level
        val wounded = livingHeroes.filter { (!inCombat || it.distanceTo(h) <= 6) && it.hp < it.maxHp / 2 }
            .sortedBy { it.hp.toFloat() / it.maxHp }
        if (wounded.isEmpty()) return log("No one nearby is badly hurt (below half HP).", LogKind.INFO)
        if (h.uses(Resource.PRESERVE_LIFE) <= 0 || !useAction()) return
        h.spend(Resource.PRESERVE_LIFE)
        log("${h.name} channels divinity: Preserve Life!", LogKind.GOOD)
        for (w in wounded) {
            if (pool <= 0) break
            val amount = minOf(pool, w.maxHp / 2 - w.hp.coerceAtLeast(0))
            if (amount <= 0) continue
            pool -= amount
            val healed = w.heal(amount)
            pendingFx += Fx.Text(w.x, w.y, "+$healed", FxKind.HEAL)
            log("${w.name} recovers $healed HP.", LogKind.GOOD)
        }
        updateFov()
    }

    // =======================================================================
    // Spells
    // =======================================================================

    /** Context of the spell being cast, for Grim Harvest, Arcane Potency and maximised damage. */
    private var castingHero: Hero? = null
    private var castingSlot = 0
    private var castMaximized = false
    private var potencyLeft = false

    fun castSpell(h: Hero, spell: Spell, target: Monster?, ally: Hero?) {
        if (spell !in h.knownSpells || h.isUnconscious || h.dead || phase == Phase.MONSTER_TURN || phase == Phase.GAME_OVER) return
        if (phase == Phase.PLAYER_TURN && current !== h) return
        if (spell.outOfCombatOnly && inCombat) {
            log("${spell.displayName} takes too long to cast in combat.", LogKind.INFO)
            return
        }
        val slot = if (spell.isCantrip) 0 else h.lowestAvailableSlot(spell.level)
        if (slot == null) {
            log("${h.name} has no spell slots left for ${spell.displayName}. Rest to recover them.", LogKind.INFO)
            return
        }
        when (spell.target) {
            SpellTarget.ENEMY, SpellTarget.ENEMY_AREA -> {
                if (target == null || target.isDead || phase != Phase.PLAYER_TURN) return
                if (h.distanceTo(target) > spell.range) {
                    log("${target.name} is out of range for ${spell.displayName} (${spell.range}).", LogKind.INFO)
                    return
                }
                if (!hasLos(h, target)) {
                    log("No line of sight.", LogKind.INFO)
                    return
                }
            }
            SpellTarget.ALLY -> {
                if (ally == null || ally.dead) return
                if (inCombat && h.distanceTo(ally) > spell.range) {
                    log(if (spell.range <= 1) "${spell.displayName} needs a touch: move next to ${ally.name}."
                        else "${ally.name} is out of range for ${spell.displayName}.", LogKind.INFO)
                    return
                }
            }
            SpellTarget.ADJACENT_AREA -> if (combatMonsters().none { it.isAdjacentTo(h) }) {
                log("No enemies within reach of ${spell.displayName}.", LogKind.INFO)
                return
            }
            SpellTarget.PARTY -> if (phase != Phase.PLAYER_TURN && !spell.outOfCombatOnly && !spell.isHealing) return
            SpellTarget.DEAD_ALLY -> {
                if (ally == null || !ally.dead) return
                if (partyGold < REVIVIFY_COST) {
                    log("Revivify needs a ${REVIVIFY_COST} gp diamond; the party has $partyGold gold.", LogKind.INFO)
                    return
                }
            }
        }
        if (spell.bonusAction && phase == Phase.PLAYER_TURN) {
            if (!bonusActionAvailable) {
                log("${h.name} has already used their bonus action.", LogKind.INFO)
                return
            }
            bonusActionAvailable = false
        } else if (!useAction()) {
            return
        }
        if (slot > 0) h.spendSlot(slot)
        val upcast = maxOf(0, slot - spell.level)
        fun scaled(base: DiceExpr) =
            base.withCount((if (spell.isCantrip) base.count * h.cantripDice else base.count) + upcast * spell.upcastDice)
        val slotNote = if (slot > 0) " (level $slot slot)" else ""
        log("${h.name} casts ${spell.displayName}$slotNote.", LogKind.INFO)
        val damaging = !spell.isHealing && spell.damageType !in setOf("sleep", "bless", "life")
        castingHero = h
        castingSlot = slot
        blessedHealerUsed = false
        potencyLeft = damaging && h.has(Feature.ARCANE_POTENCY)
        castMaximized = false
        if (damaging && slot >= 1 && h.overchannelReady && h.spend(Resource.OVERCHANNEL)) {
            h.overchannelReady = false
            castMaximized = true
            log("${h.name} overchannels the spell: maximum damage!", LogKind.GOOD)
        } else if (spell.damageType in setOf("thunder", "lightning") && h.wrathReady && h.spend(Resource.DESTRUCTIVE_WRATH)) {
            h.wrathReady = false
            castMaximized = true
            log("Destructive Wrath: the storm strikes with full fury!", LogKind.GOOD)
        }
        try {
            resolveSpell(h, spell, slot, upcast, target, ally, ::scaled)
        } finally {
            castingHero = null
            castingSlot = 0
            castMaximized = false
        }
    }

    private fun resolveSpell(h: Hero, spell: Spell, slot: Int, upcast: Int, target: Monster?, ally: Hero?, scaled: (DiceExpr) -> DiceExpr) {
        fun scaled() = scaled(spell.dice)
        when (spell) {
            Spell.FIRE_BOLT -> spellAttack(h, target!!, spell, scaled(), FxKind.FIRE)
            Spell.RAY_OF_FROST -> spellAttack(h, target!!, spell, scaled(), FxKind.FORCE)
            Spell.SCORCHING_RAY -> {
                val rays = 3 + upcast
                for (i in 0 until rays) {
                    if (target!!.isDead) break
                    spellAttack(h, target, spell, spell.dice, FxKind.FIRE)
                }
            }
            Spell.GUIDING_BOLT -> {
                val hit = spellAttack(h, target!!, spell, scaled(), FxKind.RADIANT)
                if (hit && !target.isDead) {
                    target.marked = true
                    log("${target.name} glows with mystical light.", LogKind.INFO)
                }
            }
            Spell.MAGIC_MISSILE -> {
                val darts = 3 + upcast
                repeat(darts) { pendingFx += Fx.Bolt(h.x, h.y, target!!.x, target.y, FxKind.FORCE) }
                val total = (1..darts).sumOf { rollSpellDamage(spell.dice) }
                damageMonster(target!!, total, false, spell.damageType, "$darts darts x ${spell.dice}")
            }
            Spell.SACRED_FLAME, Spell.TOLL_THE_DEAD -> {
                pendingFx += if (spell == Spell.SACRED_FLAME) Fx.Bolt(target!!.x, target.y - 2, target.x, target.y, FxKind.RADIANT)
                else Fx.Bolt(target!!.x, target.y, target.x, target.y, FxKind.FORCE)
                if (!monsterSave(h, target, spell.saveAbility!!)) {
                    val base = if (spell == Spell.TOLL_THE_DEAD && target.hp < target.maxHp) d("1d12") else spell.dice
                    val dmgDice = scaled(base)
                    damageMonster(target, rollSpellDamage(dmgDice), false, spell.damageType, "$dmgDice")
                }
            }
            Spell.BURNING_HANDS -> areaDamage(h, spell, combatMonsters().filter { it.isAdjacentTo(h) }, scaled(), FxKind.FIRE, h.x, h.y)
            Spell.THUNDERWAVE -> areaDamage(h, spell, combatMonsters().filter { it.isAdjacentTo(h) }, scaled(), FxKind.LIGHTNING, h.x, h.y)
            Spell.SHATTER, Spell.FIREBALL, Spell.CALL_LIGHTNING -> {
                val center = target!!
                val victims = combatMonsters().filter { chebyshev(it.x, it.y, center.x, center.y) <= spell.radius }
                val fx = when (spell) {
                    Spell.FIREBALL -> FxKind.FIRE
                    Spell.CALL_LIGHTNING -> FxKind.LIGHTNING
                    else -> FxKind.FORCE
                }
                pendingFx += if (spell == Spell.CALL_LIGHTNING) Fx.Bolt(center.x, center.y - 3, center.x, center.y, fx)
                else Fx.Bolt(h.x, h.y, center.x, center.y, fx)
                areaDamage(h, spell, victims, scaled(), fx, center.x, center.y)
            }
            Spell.SLEEP -> {
                val center = target!!
                var pool = dice.roll(scaled())
                pendingFx += Fx.Bolt(h.x, h.y, center.x, center.y, FxKind.FORCE)
                log("Sleep rolls $pool hit points' worth of slumber.", LogKind.ROLL)
                val candidates = combatMonsters()
                    .filter { !it.asleep && chebyshev(it.x, it.y, center.x, center.y) <= spell.radius }
                    .sortedBy { it.hp }
                for (m in candidates) {
                    if (m.hp > pool) break
                    pool -= m.hp
                    m.asleep = true
                    pendingFx += Fx.Text(m.x, m.y, "Zzz", FxKind.INFO)
                    log("${m.name} falls asleep.", LogKind.GOOD)
                }
                if (candidates.none { it.asleep }) log("No one succumbs.", LogKind.INFO)
            }
            Spell.CURE_WOUNDS, Spell.HEALING_WORD -> healHero(h, ally!!, scaled(), slot)
            Spell.MASS_HEALING_WORD -> partyInRange(h, spell).forEach { healHero(h, it, scaled(), slot) }
            Spell.PRAYER_OF_HEALING -> livingHeroes.forEach { healHero(h, it, scaled(), slot) }
            Spell.BLESS -> {
                val blessed = partyInRange(h, spell).filter { !it.isUnconscious }
                blessed.forEach {
                    it.blessed = true
                    pendingFx += Fx.Text(it.x, it.y, "blessed", FxKind.RADIANT)
                }
                log("${blessed.joinToString(", ") { it.name }} ${if (blessed.size == 1) "is" else "are"} blessed.", LogKind.GOOD)
            }
            Spell.REVIVIFY -> revivify(h, ally!!)
        }
    }

    /** Living heroes within the spell's range of the caster (everyone outside combat). */
    private fun partyInRange(h: Hero, spell: Spell) =
        livingHeroes.filter { !inCombat || it.distanceTo(h) <= spell.range }

    private fun healHero(caster: Hero, target: Hero, healDice: DiceExpr, slot: Int) {
        val disciple = if (caster.has(Feature.DISCIPLE_OF_LIFE)) 2 + slot else 0
        val healed = target.heal(dice.roll(healDice) + caster.spellMod + disciple)
        pendingFx += Fx.Text(target.x, target.y, "+$healed", FxKind.HEAL)
        log("${target.name} recovers $healed HP." + if (disciple > 0) " (Disciple of Life +$disciple)" else "", LogKind.GOOD)
        if (target !== caster && caster.has(Feature.BLESSED_HEALER) && castingHero === caster && !blessedHealerUsed) {
            blessedHealerUsed = true
            val self = caster.heal(2 + slot)
            if (self > 0) {
                pendingFx += Fx.Text(caster.x, caster.y, "+$self", FxKind.HEAL)
                log("Blessed Healer: ${caster.name} recovers $self HP.", LogKind.GOOD)
            }
        }
        updateFov()
    }

    private var blessedHealerUsed = false

    /** Rolls spell damage, honouring maximised casts and Arcane Potency (once per spell). */
    private fun rollSpellDamage(dmgDice: DiceExpr, critical: Boolean = false): Int {
        val count = if (critical) dmgDice.count * 2 else dmgDice.count
        var total = if (castMaximized) count * dmgDice.sides + dmgDice.bonus else dice.roll(dmgDice, critical)
        val caster = castingHero
        if (potencyLeft && caster != null) {
            potencyLeft = false
            total += maxOf(0, caster.spellMod)
        }
        return total
    }

    private fun areaDamage(h: Hero, spell: Spell, victims: List<Monster>, dmgDice: DiceExpr, fx: FxKind, cx: Int, cy: Int) {
        val damage = rollSpellDamage(dmgDice)
        if (victims.isEmpty()) log("The spell catches no one.", LogKind.INFO)
        for (m in victims) {
            if (spell.target == SpellTarget.ADJACENT_AREA) pendingFx += Fx.Bolt(cx, cy, m.x, m.y, fx)
            val saved = monsterSave(h, m, spell.saveAbility!!)
            damageMonster(m, if (saved) damage / 2 else damage, false, spell.damageType,
                if (saved) "$dmgDice, halved" else "$dmgDice")
        }
    }

    private fun revivify(caster: Hero, target: Hero) {
        var cost = REVIVIFY_COST
        for (h in party.sortedByDescending { it.gold }) {
            val pay = minOf(cost, h.gold)
            h.gold -= pay
            cost -= pay
        }
        graves.remove(Point(target.x, target.y))
        target.revive()
        val spot = squaresNear(Point(caster.x, caster.y), 1).firstOrNull() ?: Point(caster.x, caster.y)
        target.moveTo(spot.x, spot.y)
        resetTrail()
        updateFov()
        pendingFx += Fx.Text(target.x, target.y, "revived", FxKind.HEAL)
        log("${caster.name} lays a diamond on ${target.name}'s brow... ${target.name} breathes again!", LogKind.LEVEL)
    }

    private fun spellAttack(h: Hero, target: Monster, spell: Spell, dmgDice: DiceExpr, fx: FxKind): Boolean {
        pendingFx += Fx.Bolt(h.x, h.y, target.x, target.y, fx)
        val hit = heroAttackRoll(h, target, h.spellAttackBonus, true, "${h.name}'s ${spell.displayName}") ?: return false
        damageMonster(target, rollSpellDamage(dmgDice, hit.critical), hit.critical, spell.damageType, "$dmgDice")
        return true
    }

    /** Returns true if the monster succeeds on its saving throw against the caster's spell DC. */
    private fun monsterSave(caster: Hero, m: Monster, ability: Ability): Boolean {
        val roll = dice.d20().natural
        val total = roll + m.saveBonus(ability)
        val success = total >= caster.spellSaveDc
        log("${m.name} ${ability.name} save: d20 $roll${fmt(m.saveBonus(ability))} = $total vs DC ${caster.spellSaveDc}, " +
            if (success) "success" else "fail", LogKind.ROLL)
        if (success) pendingFx += Fx.Text(m.x, m.y, "save", FxKind.MISS)
        return success
    }

    // =======================================================================
    // Other actions
    // =======================================================================

    /** In combat a potion is an action, used on yourself or an adjacent ally; outside combat anyone can share. */
    private fun givePotion(giver: Hero, target: Hero) {
        val source = if (inCombat) giver.takeIf { it.potions > 0 } else livingHeroes.maxByOrNull { it.potions }
        if (source == null || source.potions <= 0) {
            log("No potions left.", LogKind.INFO)
            return
        }
        if (inCombat && giver.distanceTo(target) > 1) {
            log("${target.name} is too far away to hand a potion to.", LogKind.INFO)
            return
        }
        if (!useAction()) return
        source.potions--
        sound(SoundId.POTION)
        val healed = target.heal(dice.roll(d("2d4+2")))
        pendingFx += Fx.Text(target.x, target.y, "+$healed", FxKind.HEAL)
        log(if (target === giver) "${target.name} drinks a potion of healing: +$healed HP."
            else "${giver.name} gives ${target.name} a potion of healing: +$healed HP.", LogKind.GOOD)
        updateFov()
    }

    private fun dash(h: Hero) {
        if (h.charClass == CharClass.ROGUE && h.level >= 2 && bonusActionAvailable) {
            bonusActionAvailable = false
            log("${h.name} uses Cunning Action: Dash.", LogKind.INFO)
        } else if (!useAction()) return else log("${h.name} Dashes.", LogKind.INFO)
        movementLeft += h.speed
    }

    private fun disengage(h: Hero) {
        if (h.charClass == CharClass.ROGUE && h.level >= 2 && bonusActionAvailable) {
            bonusActionAvailable = false
            log("${h.name} uses Cunning Action: Disengage.", LogKind.INFO)
        } else if (!useAction()) return else log("${h.name} Disengages: no opportunity attacks this turn.", LogKind.INFO)
        disengaged = true
    }

    private fun dodge(h: Hero) {
        if (!useAction()) return
        h.dodging = true
        log("${h.name} Dodges: attacks against them have disadvantage.", LogKind.INFO)
    }

    private fun secondWind(h: Hero) {
        if (!bonusActionAvailable || !h.secondWindAvailable) return
        bonusActionAvailable = false
        h.secondWindAvailable = false
        val healed = h.heal(dice.roll(d("1d10")) + h.level)
        pendingFx += Fx.Text(h.x, h.y, "+$healed", FxKind.HEAL)
        log("${h.name} uses Second Wind: +$healed HP.", LogKind.GOOD)
        if (h.has(Feature.RALLYING_CRY)) {
            val rallied = livingHeroes.filter { it !== h && it.distanceTo(h) <= 6 }
            rallied.forEach {
                val amount = it.heal(h.level)
                if (amount > 0) pendingFx += Fx.Text(it.x, it.y, "+$amount", FxKind.HEAL)
            }
            if (rallied.isNotEmpty()) log("${h.name}'s Rallying Cry steadies the party (+${h.level} HP each).", LogKind.GOOD)
        }
    }

    private fun actionSurge(h: Hero) {
        if (actionAvailable || !h.actionSurgeAvailable) return
        h.actionSurgeAvailable = false
        actionAvailable = true
        attacksLeft = 0
        log("${h.name} uses Action Surge!", LogKind.GOOD)
    }

    private fun aim(h: Hero) {
        if (!bonusActionAvailable || movedThisTurn) return
        bonusActionAvailable = false
        aiming = true
        movementLeft = 0
        log("${h.name} takes careful aim: advantage on the next attack.", LogKind.INFO)
    }

    private fun shortRest() {
        if (phase != Phase.EXPLORING) return
        val healed = livingHeroes.sumOf { it.shortRest(dice) }
        log("The party takes a short rest and recovers $healed HP in total.", LogKind.GOOD)
        sound(SoundId.REST)
        for (h in livingHeroes.filter { it.uses(Resource.ARCANE_RECOVERY) > 0 }) {
            var budget = (h.level + 1) / 2
            var recovered = 0
            for (lvl in minOf(budget, h.maxSpellLevel) downTo 1) {
                while (budget >= lvl && h.recoverSlot(lvl)) {
                    budget -= lvl
                    recovered += lvl
                }
            }
            if (recovered > 0) {
                h.spend(Resource.ARCANE_RECOVERY)
                log("${h.name} uses Arcane Recovery, regaining $recovered levels of spell slots.", LogKind.GOOD)
            }
        }
        livingHeroes.forEach { pendingFx += Fx.Text(it.x, it.y, "${it.hitDice} HD", FxKind.HEAL) }
        wanderingMonsterCheck(1, 6)
    }

    private fun longRest() {
        if (phase != Phase.EXPLORING || longRestUsed) return
        longRestUsed = true
        livingHeroes.forEach {
            it.longRest()
            pendingFx += Fx.Text(it.x, it.y, "rested", FxKind.HEAL)
        }
        log("The party takes a long rest. HP, spell slots and features restored.", LogKind.GOOD)
        sound(SoundId.REST)
        wanderingMonsterCheck(1, 3)
    }

    private fun wanderingMonsterCheck(numerator: Int, denominator: Int) {
        if (!dice.chance(numerator, denominator)) return
        val far = dungeon.rooms.filter { r ->
            !dungeon.isVisible(r.center.x, r.center.y) && livingHeroes.all { chebyshev(r.center.x, r.center.y, it.x, it.y) > 8 }
        }
        if (far.isEmpty()) return
        val spawned = spawnEncounter(dice.pick(far), monsterPool())
        spawned.forEach { it.aware = true }
        if (spawned.isNotEmpty()) log("You hear something approaching...", LogKind.BAD)
    }

    private fun descend() {
        if (phase != Phase.EXPLORING || !onStairs(leader)) return
        sound(SoundId.DESCEND)
        depth++
        enterLevel()
    }

    private fun pickUp(h: Hero) {
        val item = dungeon.itemAt(h.x, h.y) ?: return
        dungeon.items.remove(item)
        when (item.kind) {
            ItemKind.POTION -> {
                h.potions++
                log("${h.name} finds a potion of healing.", LogKind.LOOT)
            }
            ItemKind.GOLD -> {
                val gold = dice.roll(depth + 1, 6)
                h.gold += gold
                log("${h.name} picks up $gold gold.", LogKind.LOOT)
                pendingFx += Fx.Text(h.x, h.y, "+${gold}g", FxKind.INFO)
            }
            ItemKind.CHEST -> openChest(h)
            ItemKind.TREASURE -> item.item?.let { takeTreasure(h, it) }
        }
    }

    private fun takeTreasure(h: Hero, item: Item) {
        bag += item
        log("${h.name} finds ${item.name} (${item.rarity.label.lowercase()})! It goes in the party bag.", LogKind.LOOT)
        pendingFx += Fx.Text(h.x, h.y, item.name, FxKind.LOOT)
    }

    private fun openChest(h: Hero) {
        sound(SoundId.CHEST)
        val gold = dice.roll(depth * 4, 10)
        h.gold += gold
        val found = mutableListOf("$gold gold")
        var treasure: Item? = null
        when (dice.roll(20)) {
            in 1..6 -> {
                h.potions++
                found += "a potion of healing"
            }
            in 7..8 -> {
                h.potions += 2
                found += "two potions of healing"
            }
            in 9..15 -> treasure = Loot.randomItem(dice, depth)
        }
        log("${h.name} opens a chest: ${found.joinToString(", ")}.", LogKind.LOOT)
        pendingFx += Fx.Text(h.x, h.y, "+${gold}g", FxKind.INFO)
        treasure?.let { takeTreasure(h, it) }
    }

    // =======================================================================
    // Save & load
    // =======================================================================

    /** Short description for save-slot lists. */
    fun summary(): String = "Depth $depth · " + party.joinToString(", ") {
        if (it.dead) "${it.name} (dead)" else "${it.name} L${it.level}"
    }

    /** The complete game state as a JSON-ready map. Everything not saved is derived on load. */
    fun toSave(): Map<String, Any?> {
        fun ref(c: Creature): String = if (c is Hero) "h${party.indexOf(c)}" else "m${monsters.indexOf(c)}"
        fun pt(p: Point) = listOf(p.x, p.y)
        val d = dungeon
        return linkedMapOf(
            "version" to SAVE_VERSION,
            "summary" to summary(),
            "depth" to depth,
            "phase" to phase.name,
            "leaderIndex" to leaderIndex,
            "longRestUsed" to longRestUsed,
            "kills" to kills,
            "stairsHintShown" to stairsHintShown,
            "trail" to trail.map(::pt),
            "corpses" to corpses.map(::pt),
            "graves" to graves.map(::pt),
            "log" to log.toList().takeLast(60).map { listOf(it.kind.name, it.text) },
            "heroes" to party.map { it.toSave() },
            "bag" to bag.map { it.toSave() },
            "monsters" to monsters.map { m ->
                linkedMapOf(
                    "type" to m.type.name, "maxHp" to m.baseMaxHp, "hp" to m.hp, "x" to m.x, "y" to m.y,
                    "ally" to m.ally, "acted" to m.acted, "master" to m.master?.let { party.indexOf(it) }, "extraHp" to m.extraHp, "bonusDamage" to m.bonusDamage,
                    "challenger" to m.challenger?.let { party.indexOf(it) }, "huntedBy" to m.huntedBy?.let { party.indexOf(it) },
                    "aware" to m.aware, "surprised" to m.surprised, "reaction" to m.reactionAvailable, "asleep" to m.asleep,
                    "dodging" to m.dodging, "marked" to m.marked,
                )
            },
            "dungeon" to linkedMapOf(
                "width" to d.width, "height" to d.height,
                "tiles" to buildString {
                    for (y in 0 until d.height) for (x in 0 until d.width) append(d[x, y].name.first())
                },
                "explored" to d.explored.joinToString("") { if (it) "1" else "0" },
                "rooms" to d.rooms.map { listOf(it.x, it.y, it.w, it.h) },
                "items" to d.items.map { listOf(it.x, it.y, it.kind.name, it.item?.toSave()) },
                "start" to pt(d.start), "stairs" to pt(d.stairs),
            ),
            "combat" to linkedMapOf(
                "turnOrder" to turnOrder.map(::ref),
                "initiative" to turnOrder.map { initiativeRolls[it] ?: 0 },
                "turnIndex" to turnIndex, "round" to round,
                "movementLeft" to movementLeft, "action" to actionAvailable, "bonus" to bonusActionAvailable,
                "sneakUsed" to sneakAttackUsed, "aiming" to aiming, "disengaged" to disengaged,
                "moved" to movedThisTurn, "autoEnd" to autoEndTurn, "holderRemoved" to turnHolderRemoved,
                "monsterSteps" to monsterStepsLeft, "monsterDone" to monsterDone,
            ),
        )
    }

    companion object {
        const val SAVE_VERSION = 2
        const val MOVES = "Moves"
        const val REVIVIFY_COST = 300

        /** Rebuilds a game from [toSave] output. Throws IllegalArgumentException on a bad or incompatible save. */
        fun fromSave(map: Map<String, Any?>, dice: Dice = Dice()): Game {
            val o = JsonObj(map)
            val version = o.int("version")
            require(version in 1..SAVE_VERSION) { "Save is from an incompatible version" }
            fun pt(v: Any?) = (v as List<*>).let { Point((it[0] as Number).toInt(), (it[1] as Number).toInt()) }

            val party = o.objs("heroes").map { Hero.fromSave(it, version) }
            require(party.isNotEmpty()) { "Save has no heroes" }
            val g = Game(party, dice, fresh = false)

            val dj = o.obj("dungeon")
            val d = Dungeon(dj.int("width"), dj.int("height"))
            val tiles = dj.str("tiles")
            val explored = dj.str("explored")
            require(tiles.length == d.width * d.height && explored.length == tiles.length) { "Corrupt map" }
            for (i in tiles.indices) {
                d[i % d.width, i / d.width] = Tile.entries.first { it.name.first() == tiles[i] }
                d.explored[i] = explored[i] == '1'
            }
            dj.list("rooms").forEach { r ->
                val v = (r as List<*>).map { (it as Number).toInt() }
                d.rooms += Room(v[0], v[1], v[2], v[3])
            }
            dj.list("items").forEach { it ->
                val v = it as List<*>
                d.items += FloorItem((v[0] as Number).toInt(), (v[1] as Number).toInt(), ItemKind.valueOf(v[2] as String),
                    v.getOrNull(3)?.let { Item.fromSave(it) })
            }
            d.start = pt(dj.list("start"))
            d.stairs = pt(dj.list("stairs"))
            g.dungeon = d

            o.objs("monsters").forEach { m ->
                g.monsters += Monster(MonsterType.valueOf(m.str("type")), m.int("maxHp")).apply {
                    hp = m.int("hp")
                    moveTo(m.int("x"), m.int("y"))
                    aware = m.bool("aware")
                    surprised = m.bool("surprised")
                    reactionAvailable = m.bool("reaction")
                    asleep = m.has("asleep") && m.bool("asleep")
                    if (m.has("ally")) {
                        ally = m.bool("ally")
                        acted = m.bool("acted")
                        extraHp = m.int("extraHp")
                        bonusDamage = m.int("bonusDamage")
                        if (m.has("challenger")) challenger = party.getOrNull(m.int("challenger"))
                        if (m.has("master")) master = party.getOrNull(m.int("master"))
                        if (m.has("huntedBy")) huntedBy = party.getOrNull(m.int("huntedBy"))
                    }
                    dodging = m.bool("dodging")
                    marked = m.bool("marked")
                }
            }

            if (o.has("bag")) o.list("bag").forEach { g.bag += Item.fromSave(it) }
            g.depth = o.int("depth")
            g.phase = Phase.valueOf(o.str("phase"))
            g.leaderIndex = o.int("leaderIndex").coerceIn(party.indices)
            g.longRestUsed = o.bool("longRestUsed")
            g.kills = o.int("kills")
            g.stairsHintShown = o.bool("stairsHintShown")
            o.list("trail").forEach { g.trail.addLast(pt(it)) }
            o.list("corpses").forEach { g.corpses += pt(it) }
            o.list("graves").forEach { g.graves += pt(it) }
            o.list("log").forEach { e ->
                val v = e as List<*>
                g.log.addLast(LogEntry(v[1] as String, LogKind.valueOf(v[0] as String)))
            }

            val c = o.obj("combat")
            val inits = c.ints("initiative")
            c.list("turnOrder").forEachIndexed { i, r ->
                val ref = r as String
                val idx = ref.substring(1).toInt()
                val creature: Creature = if (ref[0] == 'h') party[idx] else g.monsters[idx]
                g.turnOrder += creature
                g.initiativeRolls[creature] = inits.getOrElse(i) { 0 }
            }
            g.turnIndex = c.int("turnIndex")
            g.round = c.int("round")
            g.movementLeft = c.int("movementLeft")
            g.actionAvailable = c.bool("action")
            g.bonusActionAvailable = c.bool("bonus")
            g.sneakAttackUsed = c.bool("sneakUsed")
            g.aiming = c.bool("aiming")
            g.disengaged = c.bool("disengaged")
            g.movedThisTurn = c.bool("moved")
            g.autoEndTurn = c.bool("autoEnd")
            g.turnHolderRemoved = c.bool("holderRemoved")
            g.monsterStepsLeft = c.int("monsterSteps")
            g.monsterDone = c.bool("monsterDone")
            require(!g.inCombat || g.turnOrder.isNotEmpty()) { "Corrupt combat state" }

            g.updateFov()
            g.log("Game loaded.", LogKind.INFO)
            return g
        }

        /** Convenience wrappers for save files as text. */
        fun fromJson(text: String, dice: Dice = Dice()): Game {
            @Suppress("UNCHECKED_CAST")
            val map = Json.parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("Not a save file")
            return fromSave(map, dice)
        }
    }

    fun toJson(): String = Json.write(toSave())

    // =======================================================================
    // Helpers
    // =======================================================================

    private fun log(text: String, kind: LogKind) {
        log.addLast(LogEntry(text, kind))
        while (log.size > 200) log.removeFirst()
    }

    private fun fmt(bonus: Int) = if (bonus == 0) "" else if (bonus > 0) "+$bonus" else "$bonus"

    private fun names(ms: List<Monster>): String =
        ms.groupBy { it.name }.entries.joinToString(", ") { (n, l) ->
            when {
                l.size > 1 -> "${l.size} ${n}s"
                n.first().lowercaseChar() in "aeiou" -> "an $n"
                else -> "a $n"
            }
        }
}
