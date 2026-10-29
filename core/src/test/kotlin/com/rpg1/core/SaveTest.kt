package com.rpg1.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SaveTest {
    @Test
    fun jsonRoundTripsValuesAndEscapes() {
        val value = linkedMapOf(
            "s" to "quote \" slash \\ newline \n tab \t ünï",
            "n" to 42, "neg" to -7, "big" to 123456789012L, "d" to 1.5,
            "b" to true, "nil" to null, "list" to listOf(1, "two", false, listOf<Any>()), "empty" to mapOf<String, Any>(),
        )
        val parsed = Json.parse(Json.write(value)) as Map<*, *>
        assertEquals(value["s"], parsed["s"])
        assertEquals(42L, parsed["n"])
        assertEquals(-7L, parsed["neg"])
        assertEquals(123456789012L, parsed["big"])
        assertEquals(1.5, parsed["d"])
        assertEquals(true, parsed["b"])
        assertEquals(null, parsed["nil"])
        assertEquals(listOf(1L, "two", false, listOf<Any>()), parsed["list"])
        assertFailsWith<IllegalArgumentException> { Json.parse("{\"a\":1") }
        assertFailsWith<IllegalArgumentException> { Json.parse("[1,2] x") }
    }

    private fun newGame(seed: Int): Game {
        val b = PartyBuilder(Dice(Random(seed)))
        return Game(b.build(), Dice(Random(seed * 7)))
    }

    /** Everything except the log, which gains a "Game loaded." line on load. */
    private fun state(g: Game) = Json.write(g.toSave().filterKeys { it != "log" })

    private fun assertRoundTrip(g: Game): Game {
        val loaded = Game.fromJson(g.toJson())
        assertEquals(state(g), state(loaded))
        assertEquals("Game loaded.", loaded.log.last().text)
        assertEquals(g.activeHero?.name, loaded.activeHero?.name)
        assertEquals(g.availableActions(), loaded.availableActions())
        assertEquals(g.prompt, loaded.prompt)
        for (x in 0 until g.dungeon.width) for (y in 0 until g.dungeon.height) {
            assertEquals(g.dungeon.isVisible(x, y), loaded.dungeon.isVisible(x, y), "visibility at $x,$y")
        }
        return loaded
    }

    @Test
    fun freshGameRoundTrips() {
        assertRoundTrip(newGame(1))
    }

    @Test
    fun gamesRoundTripAtManyPointsAndKeepPlaying() {
        var sawCombat = false
        var sawMonsterTurn = false
        for (seed in 1..12) {
            val g = newGame(seed)
            // Snapshot at regular intervals, including mid-combat and mid-monster-turn.
            repeat(12) {
                Bot.play(g, 400 + seed * 37)
                if (g.inCombat) sawCombat = true
                if (g.phase == Phase.MONSTER_TURN) sawMonsterTurn = true
                assertRoundTrip(g)
            }
            // A loaded game must be fully playable.
            val loaded = Game.fromJson(g.toJson())
            Bot.play(loaded, 5_000)
        }
        assertTrue(sawCombat, "never saved during combat")
        assertTrue(sawMonsterTurn, "never saved during a monster turn")
    }

    @Test
    fun badSavesAreRejected() {
        assertFailsWith<IllegalArgumentException> { Game.fromJson("[]") }
        assertFailsWith<IllegalArgumentException> { Game.fromJson("{\"version\":999}") }
        val text = newGame(2).toJson()
        assertFailsWith<IllegalArgumentException> { Game.fromJson(text.replace("\"tiles\":\"", "\"tiles\":\"WW")) }
        assertFailsWith<IllegalArgumentException> { Game.fromJson(text.substring(0, text.length / 2)) }
    }
}
