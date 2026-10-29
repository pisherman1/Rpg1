package com.rpg1.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Drives a game with a simple bot, for smoke and balance tests. */
object Bot {
    /** Plays until game over, [maxDepth] or [maxTicks]. Fails if nothing changes for too long. */
    fun play(game: Game, maxTicks: Int, maxDepth: Int = 6): Int {
        var ticks = 0
        var stuck = 0
        while (ticks < maxTicks && game.phase != Phase.GAME_OVER && game.depth < maxDepth) {
            ticks++
            if (game.isBusy) {
                game.tick()
                continue
            }
            val before = snapshot(game)
            decide(game)
            game.tick()
            stuck = if (snapshot(game) == before) stuck + 1 else 0
            if (stuck > 200) fail("Bot stuck: phase=${game.phase} depth=${game.depth} current=${game.current?.name} idx=${game.turnIndex}/${game.turnOrder.size} active=${game.activeHero?.name} act=${game.actionAvailable} move=${game.movementLeft} actions=${game.availableActions().filter { it.enabled }.map { it.label }} heroes=${game.party.map { "${it.name}@${it.x},${it.y} hp${it.hp}" }} monsters=${game.monsters.map { "${it.name}@${it.x},${it.y}" }}")
        }
        return ticks
    }

    private fun snapshot(g: Game) = listOf(g.phase, g.party.map { listOf(it.x, it.y, it.hp) }, g.turnIndex, g.round,
        g.monsters.sumOf { it.hp }, g.monsters.sumOf { it.x * 31 + it.y }, g.depth, g.log.lastOrNull())

    private fun decide(game: Game) {
        val hero = game.activeHero ?: return
        val actions = game.availableActions()
        fun act(id: ActionId, spell: Spell? = null) =
            actions.firstOrNull { it.id == id && it.enabled && (spell == null || it.spell == spell) }
                ?.let { game.perform(it); true } ?: false

        when (game.phase) {
            Phase.EXPLORING -> {
                val hurt = game.livingHeroes.filter { it.hp < it.maxHp / 2 }
                if (hurt.isNotEmpty()) {
                    if (act(ActionId.SHORT_REST) && game.livingHeroes.none { it.hp < it.maxHp / 2 }) return
                    if (act(ActionId.LONG_REST)) return
                }
                if (act(ActionId.DESCEND)) return
                val goal = nearestFrontier(game) ?: game.dungeon.stairs
                game.tap(goal.x, goal.y)
            }
            Phase.PLAYER_TURN -> {
                if (hero.isUnconscious) return
                // Help a dying ally first if one is in reach.
                val dying = game.livingHeroes.firstOrNull { it.isUnconscious && it.distanceTo(hero) <= 1 }
                if (dying != null && game.actionAvailable) {
                    if (act(ActionId.SPELL, Spell.CURE_WOUNDS) || act(ActionId.POTION)) {
                        game.selectHero(game.party.indexOf(dying))
                        if (!game.actionAvailable) return
                    }
                }
                if (hero.hp <= hero.maxHp / 3) {
                    if (act(ActionId.SECOND_WIND)) return
                    if (act(ActionId.SPELL, Spell.CURE_WOUNDS) || act(ActionId.POTION)) {
                        game.selectHero(game.party.indexOf(hero))
                        if (!game.actionAvailable) return
                    }
                }
                val target = game.monsters.filter { game.dungeon.isVisible(it.x, it.y) }.minByOrNull { it.distanceTo(hero) }
                    ?: game.monsters.minByOrNull { it.distanceTo(hero) }
                if (target == null) {
                    act(ActionId.END_TURN)
                    return
                }
                if (game.actionAvailable) {
                    val spell = when (hero.charClass) {
                        CharClass.WIZARD -> if (hero.canCast) Spell.MAGIC_MISSILE else null
                        CharClass.CLERIC -> if (hero.canCast) Spell.GUIDING_BOLT else null
                        else -> null
                    }
                    if (spell != null && target.distanceTo(hero) <= spell.range && game.dungeon.isVisible(target.x, target.y)) {
                        act(ActionId.SPELL, spell)
                        game.tap(target.x, target.y)
                        if (!game.actionAvailable) return
                    }
                    game.tap(target.x, target.y)
                    if (game.isBusy) return
                    if (act(ActionId.ACTION_SURGE)) {
                        game.tap(target.x, target.y)
                        return
                    }
                } else if (!target.isAdjacentTo(hero) && game.movementLeft > 0) {
                    val before = hero.x to hero.y
                    game.tap(target.x, target.y)
                    if (game.isBusy || before != (hero.x to hero.y)) return
                }
                if (!game.isBusy) act(ActionId.END_TURN)
            }
            else -> {}
        }
    }

    fun nearestFrontier(game: Game): Point? {
        val d = game.dungeon
        val h = game.leader
        var best: Point? = null
        var bestDist = Int.MAX_VALUE
        for (x in 0 until d.width) for (y in 0 until d.height) {
            if (!d.isExplored(x, y) || !d[x, y].walkable) continue
            val frontier = Dungeon.DIRS.any { (dx, dy) -> d.inBounds(x + dx, y + dy) && !d.isExplored(x + dx, y + dy) }
            if (!frontier && d.itemAt(x, y) == null) continue
            val dist = chebyshev(x, y, h.x, h.y)
            if (dist in 1 until bestDist &&
                d.findPath(h.x, h.y, x, y, { px, py -> game.monsterAt(px, py) != null || !d.isExplored(px, py) }) != null
            ) {
                best = Point(x, y)
                bestDist = dist
            }
        }
        return best
    }
}

class GameTest {
    private fun newGame(seed: Int, classes: List<CharClass> = CharClass.entries, races: List<Race> = Race.entries): Game {
        val dice = Dice(Random(seed))
        val party = (0 until PARTY_SIZE).map { i ->
            Hero.create("H$i", races[i % races.size], classes[i % classes.size], List(6) { dice.abilityScore() })
        }
        return Game(party, dice)
    }

    @Test
    fun partyStartsTogetherOnDistinctSquares() {
        repeat(20) { seed ->
            val game = newGame(seed)
            val squares = game.party.map { it.x to it.y }
            assertEquals(4, squares.toSet().size, "seed $seed: $squares")
            game.party.forEach { assertTrue(it.distanceTo(game.leader) <= 3) }
        }
    }

    @Test
    fun followersKeepUpWithTheLeader() {
        val game = newGame(3)
        repeat(300) {
            if (game.phase != Phase.EXPLORING) return
            if (!game.isBusy) Bot.nearestFrontier(game)?.let { game.tap(it.x, it.y) }
            game.tick()
            val squares = game.livingHeroes.map { it.x to it.y }
            assertEquals(squares.size, squares.toSet().size, "heroes overlap: $squares")
            game.livingHeroes.forEach { assertTrue(game.dungeon[it.x, it.y].walkable) }
        }
    }

    @Test
    fun botPlaysManyPartiesWithoutCrashingOrStalling() {
        var deepest = 0
        var levelUps = 0
        for (seed in 1..40) {
            // Mix up the party makeup, including doubled-up classes.
            val classes = List(4) { CharClass.entries[(seed + it * (seed % 3 + 1)) % 4] }
            val game = newGame(seed, classes)
            Bot.play(game, 40_000)
            deepest = maxOf(deepest, game.depth)
            levelUps += game.party.count { it.level > 1 }
            game.party.forEach {
                assertTrue(it.hp in 0..it.maxHp)
                if (it.dead) assertEquals(0, it.hp)
            }
            if (game.phase == Phase.GAME_OVER) assertTrue(game.consciousHeroes.isEmpty())
        }
        assertTrue(deepest >= 3, "deepest depth reached was $deepest")
        assertTrue(levelUps > 0, "nobody levelled up")
    }

    @Test
    fun seeingAMonsterStartsCombatWithEveryoneInInitiative() {
        val game = newGame(5)
        var ticks = 0
        while (game.phase == Phase.EXPLORING && ticks < 20_000 && game.depth == 1) {
            ticks++
            if (!game.isBusy) (Bot.nearestFrontier(game) ?: game.dungeon.stairs).let { game.tap(it.x, it.y) }
            game.tick()
        }
        assertTrue(game.inCombat)
        assertTrue(game.turnOrder.containsAll(game.livingHeroes))
        assertTrue(game.turnOrder.any { it is Monster })
        assertTrue(game.log.any { it.text.startsWith("Initiative:") })
    }

    @Test
    fun portraitTapChangesLeaderOutsideCombat() {
        val game = newGame(2)
        game.selectHero(2)
        assertEquals(2, game.leaderIndex)
        assertEquals(game.party[2], game.activeHero)
    }

    @Test
    fun potionCanBeGivenToAnyoneOutsideCombat() {
        val game = newGame(4)
        val patient = game.party[3]
        patient.hp = 1
        val potion = game.availableActions().first { it.id == ActionId.POTION }
        val before = game.livingHeroes.sumOf { it.potions }
        game.perform(potion)
        game.selectHero(3)
        assertTrue(patient.hp > 1)
        assertEquals(before - 1, game.livingHeroes.sumOf { it.potions })
    }

    @Test
    fun longRestOncePerDepth() {
        val game = newGame(6)
        game.perform(game.availableActions().first { it.id == ActionId.LONG_REST })
        assertEquals(false, game.availableActions().first { it.id == ActionId.LONG_REST }.enabled)
    }
}
