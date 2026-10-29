package com.rpg1.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DungeonTest {
    @Test
    fun everyRoomAndTheStairsAreReachable() {
        repeat(50) { seed ->
            val d = DungeonGenerator.generate(Dice(Random(seed)))
            assertTrue(d.rooms.size >= 4, "seed $seed made ${d.rooms.size} rooms")
            assertEquals(Tile.STAIRS, d[d.stairs.x, d.stairs.y])
            assertNotNull(d.findPath(d.start.x, d.start.y, d.stairs.x, d.stairs.y), "seed $seed")
            for (r in d.rooms) assertNotNull(d.findPath(d.start.x, d.start.y, r.center.x, r.center.y))
        }
    }

    private fun openRoom(): Dungeon {
        val d = Dungeon(10, 10)
        for (x in 1..8) for (y in 1..8) d[x, y] = Tile.FLOOR
        return d
    }

    @Test
    fun diagonalMovesCostOneSquare() {
        val path = openRoom().findPath(1, 1, 5, 5)!!
        assertEquals(4, path.size)
        assertEquals(Point(5, 5), path.last())
    }

    @Test
    fun cannotCutCornersAroundWalls() {
        val d = openRoom()
        d[2, 1] = Tile.WALL
        assertFalse(d.canStep(1, 1, 2, 2))
        assertTrue(d.canStep(1, 1, 1, 2))
    }

    @Test
    fun stopAdjacentEndsNextToGoal() {
        val path = openRoom().findPath(1, 1, 6, 1, stopAdjacent = true)!!
        assertEquals(Point(5, 1), path.last())
    }

    @Test
    fun blockedSquaresAreAvoided() {
        val d = openRoom()
        for (y in 1..8) if (y != 8) d[4, y] = Tile.WALL
        val path = d.findPath(1, 1, 7, 1, blocked = { x, y -> x == 4 && y == 8 })
        assertNull(path)
    }

    @Test
    fun wallsBlockSight() {
        val d = openRoom()
        for (y in 1..8) d[5, y] = Tile.WALL
        d.computeFov(2, 4, 8)
        assertTrue(d.isVisible(4, 4))
        assertTrue(d.isVisible(5, 4)) // the wall itself is seen
        assertFalse(d.isVisible(7, 4))
        assertFalse(d.hasLineOfSight(2, 4, 7, 4))
        assertTrue(d.hasLineOfSight(2, 4, 4, 6))
    }
}
