package com.rpg1.core

enum class LogKind { INFO, ROLL, GOOD, BAD, LOOT, LEVEL }

data class LogEntry(val text: String, val kind: LogKind)

enum class FxKind { DAMAGE, HEAL, MISS, CRIT, FIRE, RADIANT, FORCE, ARROW, INFO }

/** Visual effects for the UI to animate; the game only records them. */
sealed class Fx {
    data class Text(val x: Int, val y: Int, val text: String, val kind: FxKind) : Fx()
    data class Bolt(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val kind: FxKind) : Fx()
}

enum class Phase { EXPLORING, PLAYER_TURN, MONSTER_TURN, GAME_OVER }

enum class ActionId {
    END_TURN, DASH, DODGE, DISENGAGE, POTION, SECOND_WIND, ACTION_SURGE, AIM,
    RANGED, SPELL, SHORT_REST, LONG_REST, DESCEND,
}

data class GameAction(
    val id: ActionId,
    val label: String,
    val enabled: Boolean = true,
    /** Currently armed / toggled on. */
    val active: Boolean = false,
    val spell: Spell? = null,
)

/**
 * The whole game state for a party of heroes. The UI calls [tap], [selectHero] and [perform]
 * for input and [tick] on a timer; everything else is read-only state for rendering.
 */
class Game(val party: List<Hero>, val dice: Dice = Dice()) {
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
    /** The creature whose turn it was left the order; turnIndex already points at the next one. */
    private var turnHolderRemoved = false

    private var monsterStepsLeft = 0
    private var monsterDone = false

    // --- Targeting & movement ----------------------------------------------
    var armedSpell: Spell? = null
        private set
    var armedRanged = false
        private set
    var armedPotion = false
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

    private fun combatMonsters() = turnOrder.filterIsInstance<Monster>().filter { !it.isDead }

    private fun onStairs(h: Hero) = h.x == dungeon.stairs.x && h.y == dungeon.stairs.y

    fun drainFx(): List<Fx> = pendingFx.toList().also { pendingFx.clear() }

    val isBusy get() = walkPath.isNotEmpty() || phase == Phase.MONSTER_TURN || autoEndTurn

    fun initiativeOf(c: Creature): Int? = initiativeRolls[c]

    // =======================================================================
    // Level setup
    // =======================================================================

    private fun enterLevel() {
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
            return when {
                phase == Phase.GAME_OVER -> "The party has fallen. Tap New Party to try again."
                armedSpell?.target == SpellTarget.ALLY -> "Tap a hero (or portrait) to heal"
                armedSpell != null -> "Tap a target for ${armedSpell!!.displayName}"
                armedPotion -> "Tap a hero (or portrait) to give the potion"
                armedRanged -> "Tap a target for the ${h?.charClass?.rangedWeapon?.name}"
                phase == Phase.MONSTER_TURN -> "${current?.name}'s turn..."
                h != null && phase == Phase.PLAYER_TURN && h.isUnconscious -> "${h.name} is dying! Death saves: " +
                    "${h.deathSuccesses} successes, ${h.deathFailures} failures"
                h != null && phase == Phase.PLAYER_TURN -> "${h.name}'s turn: tap to move or attack"
                onStairs(leader) -> "Stairs! Tap Descend to go deeper"
                else -> "Tap to explore. Tap a portrait to change leader"
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
                for (spell in cls.spells.filter { it.target == SpellTarget.ALLY }) {
                    actions += GameAction(ActionId.SPELL, spellLabel(h, spell), h.canCast && wounded, armedSpell == spell, spell)
                }
                val needsRest = livingHeroes.any { it.hp < it.maxHp || !it.secondWindAvailable || !it.actionSurgeAvailable }
                actions += GameAction(ActionId.SHORT_REST, "Short Rest", needsRest)
                actions += GameAction(ActionId.LONG_REST, "Long Rest", !longRestUsed)
            }
            Phase.PLAYER_TURN -> {
                val mine = !h.isUnconscious
                val bonusCunning = cls == CharClass.ROGUE && h.level >= 2 && bonusActionAvailable
                actions += GameAction(ActionId.END_TURN, "End Turn", !autoEndTurn)
                for (spell in cls.spells) {
                    val ok = mine && actionAvailable && (spell.isCantrip || h.canCast)
                    actions += GameAction(ActionId.SPELL, spellLabel(h, spell), ok, armedSpell == spell, spell)
                }
                cls.rangedWeapon?.let {
                    actions += GameAction(ActionId.RANGED, it.name.substringAfterLast(' '), mine && actionAvailable, armedRanged)
                }
                if (cls == CharClass.FIGHTER) {
                    actions += GameAction(ActionId.SECOND_WIND, "2nd Wind", mine && bonusActionAvailable && h.secondWindAvailable)
                    if (h.level >= 2) {
                        actions += GameAction(ActionId.ACTION_SURGE, "Surge", mine && !actionAvailable && h.actionSurgeAvailable)
                    }
                }
                if (cls == CharClass.ROGUE) {
                    actions += GameAction(ActionId.AIM, "Aim", mine && bonusActionAvailable && !movedThisTurn && !aiming, aiming)
                }
                actions += GameAction(ActionId.DASH, if (bonusCunning) "Dash (B)" else "Dash", mine && (actionAvailable || bonusCunning))
                actions += GameAction(ActionId.DISENGAGE, if (bonusCunning) "Disengage(B)" else "Disengage",
                    mine && !disengaged && (actionAvailable || bonusCunning), disengaged)
                actions += GameAction(ActionId.DODGE, "Dodge", mine && actionAvailable)
                actions += GameAction(ActionId.POTION, "Potion x${h.potions}", mine && actionAvailable && h.potions > 0, armedPotion)
            }
        }
        return actions
    }

    private fun spellLabel(h: Hero, spell: Spell): String =
        if (spell.isCantrip) spell.shortName else "${spell.shortName} ${h.spellSlots.sum()}"

    private fun totalPotions() = livingHeroes.sumOf { it.potions }

    private fun disarm() {
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
            ActionId.SPELL -> {
                val spell = action.spell ?: return
                val wasArmed = armedSpell == spell
                disarm()
                when (spell.target) {
                    SpellTarget.ADJACENT_AREA -> castSpell(h, spell, null, null)
                    else -> armedSpell = if (wasArmed) null else spell
                }
            }
        }
    }

    /** Player tapped a hero's portrait (or the hero on the map). */
    fun selectHero(index: Int) {
        val target = party.getOrNull(index) ?: return
        if (target.dead || phase == Phase.GAME_OVER || phase == Phase.MONSTER_TURN || autoEndTurn) return
        val h = activeHero ?: return
        when {
            armedPotion -> {
                armedPotion = false
                givePotion(h, target)
            }
            armedSpell?.target == SpellTarget.ALLY -> {
                val spell = armedSpell!!
                armedSpell = null
                castSpell(h, spell, null, target)
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
            if (armedPotion || armedSpell?.target == SpellTarget.ALLY || (phase == Phase.EXPLORING && ally !== h)) {
                selectHero(party.indexOf(ally))
                return
            }
        }

        val target = monsterAt(x, y)?.takeIf { dungeon.isVisible(x, y) }
        armedSpell?.let { spell ->
            disarm()
            if (target != null && spell.target == SpellTarget.ENEMY) castSpell(h, spell, target, null)
            else log("Cancelled ${spell.displayName}.", LogKind.INFO)
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
            if (phase == Phase.PLAYER_TURN && actionAvailable && tryDefaultRangedAction(h, target)) return
            val path = dungeon.findPath(h.x, h.y, x, y, ::isBlockedForHero, stopAdjacent = true) ?: return
            queueWalk(path, target)
            return
        }

        if (!dungeon.isExplored(x, y) || !dungeon[x, y].walkable) return
        val path = dungeon.findPath(h.x, h.y, x, y, ::isBlockedForHero) ?: return
        queueWalk(path, null)
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
            while (reachable.isNotEmpty() && heroAt(reachable.last().x, reachable.last().y) != null) reachable.removeLast()
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
    private fun isBlockedForHero(x: Int, y: Int) = monsterAt(x, y) != null || !dungeon.isExplored(x, y)

    /**
     * Tapping a distant foe: rogues shoot, casters use their attack cantrip, and fighters
     * shoot only when they can't reach melee this turn.
     */
    private fun tryDefaultRangedAction(h: Hero, target: Monster): Boolean {
        val cls = h.charClass
        val canCloseIn = (dungeon.findPath(h.x, h.y, target.x, target.y, ::isBlockedForHero, stopAdjacent = true)?.size
            ?: Int.MAX_VALUE) <= movementLeft
        if (cls == CharClass.ROGUE || (cls.rangedWeapon != null && !canCloseIn)) {
            val bow = cls.rangedWeapon!!
            if (h.distanceTo(target) <= bow.range && hasLos(h, target)) {
                rangedAttack(h, target)
                return true
            }
        }
        val cantrip = cls.spells.firstOrNull { it.isCantrip && it.target == SpellTarget.ENEMY } ?: return false
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
        val blocked = monsterAt(next.x, next.y) != null || (ally != null && phase == Phase.PLAYER_TURN && isLast)
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
                it.isAdjacentTo(h) && chebyshev(it.x, it.y, next.x, next.y) > 1 && it.reactionAvailable && !it.surprised
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
        }
        updateFov()
        pickUp(h)
        if (phase == Phase.EXPLORING) {
            moveHuntersWhileExploring()
        } else {
            joinNewcomers()
        }
        if (walkPath.isEmpty()) {
            val t = walkTarget
            walkTarget = null
            if (t != null && phase == Phase.PLAYER_TURN && !t.isDead && h.isAdjacentTo(t) && actionAvailable) {
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

    private fun updateFov() = dungeon.computeFov(consciousHeroes.map { Triple(it.x, it.y, it.race.sightRadius) })

    /** Aware monsters hunt the party between fights; seeing any monster starts combat. */
    private fun moveHuntersWhileExploring(): Boolean {
        var moved = false
        for (m in monsters.filter { it.aware && !it.isDead }) {
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
        val seen = monsters.filter { !it.isDead && dungeon.isVisible(it.x, it.y) }
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
        val all = livingHeroes + seen
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
        val newcomers = monsters.filter { !it.isDead && it !in turnOrder && dungeon.isVisible(it.x, it.y) }
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
        (current as? Monster)?.surprised = false
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
        val awake = consciousHeroes
        val ranged = m.type.rangedAttack
        val adjacent = awake.filter { m.isAdjacentTo(it) }
        val shootable = if (ranged == null) emptyList() else
            awake.filter { m.distanceTo(it) <= ranged.range && hasLos(m, it) }
        when {
            adjacent.isNotEmpty() -> {
                monsterAttack(m, m.type.melee, adjacent.minBy { it.hp * 100 + dice.roll(99) })
                monsterDone = true
            }
            shootable.isNotEmpty() -> {
                monsterAttack(m, ranged!!, shootable.minBy { m.distanceTo(it) * 100 + dice.roll(99) })
                monsterDone = true
            }
            monsterStepsLeft > 0 -> {
                val step = awake.sortedBy { m.distanceTo(it) }.firstNotNullOfOrNull { prey ->
                    dungeon.findPath(m.x, m.y, prey.x, prey.y, { x, y -> isOccupied(x, y) }, stopAdjacent = true, maxNodes = 900)
                        ?.firstOrNull()
                }
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

    private fun meleeAttack(h: Hero, target: Monster) {
        if (phase != Phase.PLAYER_TURN) return
        if (!useAction()) return
        weaponAttack(h, target, h.charClass.meleeWeapon)
    }

    private fun rangedAttack(h: Hero, target: Monster) {
        if (phase != Phase.PLAYER_TURN) return
        val weapon = h.charClass.rangedWeapon ?: return
        if (h.distanceTo(target) > weapon.range) {
            log("${target.name} is out of range (${weapon.range} squares).", LogKind.INFO)
            return
        }
        if (!hasLos(h, target)) {
            log("No clear shot.", LogKind.INFO)
            return
        }
        if (!useAction()) return
        weaponAttack(h, target, weapon)
    }

    private fun weaponAttack(h: Hero, target: Monster, weapon: Weapon) {
        if (weapon.ranged) pendingFx += Fx.Bolt(h.x, h.y, target.x, target.y, FxKind.ARROW)
        val label = "${h.name}'s " + if (h.weaponBonus > 0) "+${h.weaponBonus} ${weapon.name}" else weapon.name
        val roll = heroAttackRoll(h, target, h.attackBonus(weapon), weapon.ranged, label) ?: return
        var damage = dice.roll(weapon.damage, roll.isCritical) + h.damageBonus(weapon)
        var detail = "${weapon.damage}${fmt(h.damageBonus(weapon))}"
        // Sneak Attack: advantage, or an ally next to the target (and no disadvantage).
        val allyAdjacent = consciousHeroes.any { it !== h && it.isAdjacentTo(target) }
        if (h.charClass == CharClass.ROGUE && !sneakAttackUsed && roll.mode != RollMode.DISADVANTAGE &&
            (roll.mode == RollMode.ADVANTAGE || allyAdjacent || target.surprised)
        ) {
            sneakAttackUsed = true
            damage += dice.roll(DiceExpr(h.sneakAttackDice, 6), roll.isCritical)
            detail += " + Sneak Attack ${h.sneakAttackDice}d6"
        }
        damageMonster(target, maxOf(1, damage), roll.isCritical, weapon.damageType, detail)
    }

    /** Rolls a hero's attack and logs it. Returns the roll on a hit, null on a miss. */
    private fun heroAttackRoll(h: Hero, target: Monster, bonus: Int, ranged: Boolean, label: String): D20Roll? {
        val advantage = target.marked || aiming
        val enemyAdjacent = combatMonsters().any { it.isAdjacentTo(h) }
        val disadvantage = target.dodging || (ranged && enemyAdjacent)
        val roll = dice.d20(RollMode.of(advantage, disadvantage), h.lucky)
        target.marked = false
        aiming = false
        val total = roll.natural + bonus
        val hit = roll.isCritical || (!roll.isFumble && total >= target.armorClass)
        val modeNote = when (roll.mode) {
            RollMode.ADVANTAGE -> " (adv)"
            RollMode.DISADVANTAGE -> " (disadv)"
            RollMode.NORMAL -> ""
        }
        val verdict = when {
            roll.isCritical -> "CRITICAL HIT!"
            hit -> "hit"
            roll.isFumble -> "natural 1, miss"
            else -> "miss"
        }
        log("$label vs ${target.name}: ${roll.describe()}${fmt(bonus)} = $total vs AC ${target.armorClass}$modeNote, $verdict",
            if (hit) LogKind.ROLL else LogKind.INFO)
        if (!hit) pendingFx += Fx.Text(target.x, target.y, "miss", FxKind.MISS)
        return if (hit) roll else null
    }

    private fun damageMonster(target: Monster, amount: Int, critical: Boolean, type: String, detail: String) {
        if (target.isDead) return
        target.hp -= amount
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
        kills++
        removeFromTurnOrder(m)
        monsters.remove(m)
        corpses += Point(m.x, m.y)
        val share = m.type.xp / livingHeroes.size.coerceAtLeast(1)
        log("${m.name} is slain! +$share XP each", LogKind.GOOD)
        for (h in livingHeroes) {
            h.gainXp(share, dice).forEach {
                log(it, LogKind.LEVEL)
                pendingFx += Fx.Text(h.x, h.y, "LEVEL UP", FxKind.HEAL)
            }
        }
        if (dungeon.itemAt(m.x, m.y) == null && dungeon[m.x, m.y] == Tile.FLOOR) {
            when (dice.roll(12)) {
                1, 2 -> dungeon.items += FloorItem(m.x, m.y, ItemKind.POTION)
                3, 4, 5 -> dungeon.items += FloorItem(m.x, m.y, ItemKind.GOLD)
            }
        }
        if (phase == Phase.PLAYER_TURN) endCombatIfOver()
    }

    private fun monsterAttack(m: Monster, attack: MonsterAttack, target: Hero) {
        if (attack.ranged) pendingFx += Fx.Bolt(m.x, m.y, target.x, target.y, FxKind.ARROW)
        val advantage = target.isUnconscious
        val disadvantage = target.dodging
        val roll = dice.d20(RollMode.of(advantage, disadvantage))
        val total = roll.natural + attack.toHit
        val hit = roll.isCritical || (!roll.isFumble && total >= target.armorClass)
        val critical = hit && (roll.isCritical || (target.isUnconscious && !attack.ranged))
        log("${m.name} ${attack.name} on ${target.name}: ${roll.describe()}${fmt(attack.toHit)} = $total vs AC ${target.armorClass}, " +
            if (critical) "CRITICAL HIT!" else if (hit) "hit" else "miss", if (hit) LogKind.BAD else LogKind.INFO)
        if (!hit) {
            pendingFx += Fx.Text(target.x, target.y, "miss", FxKind.MISS)
            return
        }
        val damage = maxOf(1, dice.roll(attack.damage, critical))
        damageHero(target, damage, critical, "${attack.damageType} from ${m.name}")
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
                updateFov()
                checkTotalPartyKill()
            }
        }
    }

    private fun deathSave(h: Hero) {
        if (h.stable) {
            log("${h.name} is stable but unconscious.", LogKind.INFO)
            return
        }
        val roll = dice.d20(lucky = h.lucky)
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
    // Spells
    // =======================================================================

    fun castSpell(h: Hero, spell: Spell, target: Monster?, ally: Hero?) {
        if (spell !in h.charClass.spells || h.isUnconscious || phase == Phase.MONSTER_TURN || phase == Phase.GAME_OVER) return
        val slot = if (spell.isCantrip) 0 else h.lowestAvailableSlot()
        if (slot == null) {
            log("${h.name} has no spell slots left. Rest to recover them.", LogKind.INFO)
            return
        }
        when (spell.target) {
            SpellTarget.ENEMY -> {
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
                    log("${spell.displayName} needs a touch: move next to ${ally.name}.", LogKind.INFO)
                    return
                }
            }
            SpellTarget.ADJACENT_AREA -> if (combatMonsters().none { it.isAdjacentTo(h) }) {
                log("No enemies within reach of ${spell.displayName}.", LogKind.INFO)
                return
            }
        }
        if (!useAction()) return
        if (slot > 0) h.spendSlot(slot)
        val slotNote = if (slot > 0) " (level $slot slot)" else ""
        log("${h.name} casts ${spell.displayName}$slotNote.", LogKind.INFO)

        when (spell) {
            Spell.FIRE_BOLT -> spellAttack(h, target!!, spell, spell.dice.withCount(h.cantripDice), FxKind.FIRE)
            Spell.GUIDING_BOLT -> {
                val hit = spellAttack(h, target!!, spell, spell.dice.withCount(4 + slot - 1), FxKind.RADIANT)
                if (hit && !target.isDead) {
                    target.marked = true
                    log("${target.name} glows with mystical light.", LogKind.INFO)
                }
            }
            Spell.MAGIC_MISSILE -> {
                val darts = 2 + slot
                repeat(darts) { pendingFx += Fx.Bolt(h.x, h.y, target!!.x, target.y, FxKind.FORCE) }
                val total = (1..darts).sumOf { dice.roll(spell.dice) }
                damageMonster(target!!, total, false, spell.damageType, "$darts darts x ${spell.dice}")
            }
            Spell.SACRED_FLAME -> {
                pendingFx += Fx.Bolt(target!!.x, target.y - 2, target.x, target.y, FxKind.RADIANT)
                if (!monsterSave(h, target, Ability.DEX)) {
                    val dmgDice = spell.dice.withCount(h.cantripDice)
                    damageMonster(target, dice.roll(dmgDice), false, spell.damageType, "$dmgDice")
                }
            }
            Spell.BURNING_HANDS -> {
                val dmgDice = spell.dice.withCount(3 + slot - 1)
                val damage = dice.roll(dmgDice)
                for (m in combatMonsters().filter { it.isAdjacentTo(h) }) {
                    pendingFx += Fx.Bolt(h.x, h.y, m.x, m.y, FxKind.FIRE)
                    val saved = monsterSave(h, m, Ability.DEX)
                    damageMonster(m, if (saved) damage / 2 else damage, false, spell.damageType,
                        if (saved) "$dmgDice, halved" else "$dmgDice")
                }
            }
            Spell.CURE_WOUNDS -> {
                val healed = ally!!.heal(dice.roll(spell.dice.withCount(slot)) + h.spellMod)
                pendingFx += Fx.Text(ally.x, ally.y, "+$healed", FxKind.HEAL)
                log("${ally.name} recovers $healed HP.", LogKind.GOOD)
                updateFov()
            }
        }
    }

    private fun spellAttack(h: Hero, target: Monster, spell: Spell, dmgDice: DiceExpr, fx: FxKind): Boolean {
        pendingFx += Fx.Bolt(h.x, h.y, target.x, target.y, fx)
        val roll = heroAttackRoll(h, target, h.spellAttackBonus, true, "${h.name}'s ${spell.displayName}") ?: return false
        damageMonster(target, dice.roll(dmgDice, roll.isCritical), roll.isCritical, spell.damageType, "$dmgDice")
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
    }

    private fun actionSurge(h: Hero) {
        if (actionAvailable || !h.actionSurgeAvailable) return
        h.actionSurgeAvailable = false
        actionAvailable = true
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
        }
    }

    private fun openChest(h: Hero) {
        val gold = dice.roll(depth * 4, 10)
        h.gold += gold
        val found = mutableListOf("$gold gold")
        when (dice.roll(20)) {
            in 1..8 -> {
                h.potions++
                found += "a potion of healing"
            }
            in 9..11 -> livingHeroes.filter { it.weaponBonus < 3 }.minByOrNull { it.weaponBonus }?.let {
                it.weaponBonus++
                found += "a +${it.weaponBonus} ${it.charClass.meleeWeapon.name.lowercase()} for ${it.name}"
            }
            in 12..13 -> livingHeroes.filter { it.armorBonus < 2 }.minByOrNull { it.armorBonus }?.let {
                it.armorBonus++
                found += "+${it.armorBonus} armor for ${it.name}"
            }
            in 14..15 -> {
                h.potions += 2
                found += "two potions of healing"
            }
        }
        log("${h.name} opens a chest: ${found.joinToString(", ")}.", LogKind.LOOT)
        pendingFx += Fx.Text(h.x, h.y, "+${gold}g", FxKind.INFO)
    }

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
