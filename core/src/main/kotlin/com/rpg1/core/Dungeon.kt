package com.rpg1.core

enum class Tile(val walkable: Boolean, val transparent: Boolean) {
    WALL(false, false),
    FLOOR(true, true),
    STAIRS(true, true),
}

data class Point(val x: Int, val y: Int)

data class Room(val x: Int, val y: Int, val w: Int, val h: Int) {
    val center get() = Point(x + w / 2, y + h / 2)

    fun intersects(o: Room, margin: Int = 1) =
        x - margin < o.x + o.w && x + w + margin > o.x && y - margin < o.y + o.h && y + h + margin > o.y

    fun contains(px: Int, py: Int) = px in x until x + w && py in y until y + h
}

enum class ItemKind { CHEST, POTION, GOLD, TREASURE }

/** Something lying on the floor; a TREASURE holds a specific magic [item]. */
data class FloorItem(val x: Int, val y: Int, val kind: ItemKind, val item: Item? = null)

class Dungeon(val width: Int, val height: Int) {
    private val tiles = Array(width * height) { Tile.WALL }
    val explored = BooleanArray(width * height)
    val visible = BooleanArray(width * height)
    val rooms = mutableListOf<Room>()
    val items = mutableListOf<FloorItem>()
    var start = Point(1, 1)
    var stairs = Point(1, 1)

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

    operator fun get(x: Int, y: Int): Tile = if (inBounds(x, y)) tiles[y * width + x] else Tile.WALL

    operator fun set(x: Int, y: Int, tile: Tile) {
        if (inBounds(x, y)) tiles[y * width + x] = tile
    }

    fun isVisible(x: Int, y: Int) = inBounds(x, y) && visible[y * width + x]
    fun isExplored(x: Int, y: Int) = inBounds(x, y) && explored[y * width + x]

    fun itemAt(x: Int, y: Int) = items.firstOrNull { it.x == x && it.y == y }

    /** Recomputes the visible set from a single viewer. */
    fun computeFov(ox: Int, oy: Int, radius: Int) = computeFov(listOf(Triple(ox, oy, radius)))

    /** Recomputes the visible set as the union of what each (x, y, radius) viewer sees. */
    fun computeFov(viewers: List<Triple<Int, Int, Int>>) {
        visible.fill(false)
        for ((x, y, r) in viewers) castFov(x, y, r)
    }

    /** Casts rays from (ox, oy) to every square on the edge of the sight square. */
    private fun castFov(ox: Int, oy: Int, radius: Int) {
        val r2 = radius * radius + radius
        for (tx in ox - radius..ox + radius) for (ty in oy - radius..oy + radius) {
            val onEdge = tx == ox - radius || tx == ox + radius || ty == oy - radius || ty == oy + radius
            if (!onEdge) continue
            for ((x, y) in line(ox, oy, tx, ty)) {
                if (!inBounds(x, y)) break
                val dx = x - ox
                val dy = y - oy
                if (dx * dx + dy * dy > r2) break
                visible[y * width + x] = true
                explored[y * width + x] = true
                if (!this[x, y].transparent) break
            }
        }
    }

    /** True if nothing opaque lies strictly between the two squares. */
    fun hasLineOfSight(x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
        line(x1, y1, x2, y2).drop(1).dropLast(1).all { (x, y) -> this[x, y].transparent }

    /**
     * Breadth-first search with 8-way movement (no cutting corners around walls).
     * Returns the steps from start (exclusive) to goal (inclusive), or null.
     * [blocked] squares can't be entered; if [stopAdjacent], the path ends next to the goal.
     */
    fun findPath(
        sx: Int, sy: Int, gx: Int, gy: Int,
        blocked: (Int, Int) -> Boolean = { _, _ -> false },
        stopAdjacent: Boolean = false,
        maxNodes: Int = width * height,
    ): List<Point>? {
        if (sx == gx && sy == gy) return emptyList()
        if (stopAdjacent && chebyshev(sx, sy, gx, gy) == 1) return emptyList()
        val came = IntArray(width * height) { -1 }
        val startIdx = sy * width + sx
        came[startIdx] = startIdx
        val queue = ArrayDeque<Int>()
        queue.add(startIdx)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val cur = queue.removeFirst()
            visited++
            val cx = cur % width
            val cy = cur / width
            for ((dx, dy) in DIRS) {
                val nx = cx + dx
                val ny = cy + dy
                if (!inBounds(nx, ny)) continue
                val ni = ny * width + nx
                if (came[ni] != -1) continue
                if (!this[nx, ny].walkable) continue
                if (dx != 0 && dy != 0 && (!this[cx + dx, cy].walkable || !this[cx, cy + dy].walkable)) continue
                val isGoal = nx == gx && ny == gy
                val reached = if (stopAdjacent) chebyshev(nx, ny, gx, gy) == 1 && !blocked(nx, ny) else isGoal
                if (!isGoal && blocked(nx, ny)) continue
                if (stopAdjacent && isGoal) continue
                came[ni] = cur
                if (reached) {
                    val path = ArrayList<Point>()
                    var p = ni
                    while (p != startIdx) {
                        path.add(Point(p % width, p / width))
                        p = came[p]
                    }
                    return path.asReversed()
                }
                queue.add(ni)
            }
        }
        return null
    }

    fun canStep(fx: Int, fy: Int, tx: Int, ty: Int): Boolean {
        if (chebyshev(fx, fy, tx, ty) != 1 || !this[tx, ty].walkable) return false
        val dx = tx - fx
        val dy = ty - fy
        return dx == 0 || dy == 0 || (this[fx + dx, fy].walkable && this[fx, fy + dy].walkable)
    }

    companion object {
        val DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

        /** Bresenham line including both endpoints. */
        fun line(x1: Int, y1: Int, x2: Int, y2: Int): List<Point> {
            val pts = ArrayList<Point>()
            var x = x1
            var y = y1
            val dx = kotlin.math.abs(x2 - x1)
            val dy = -kotlin.math.abs(y2 - y1)
            val sx = if (x1 < x2) 1 else -1
            val sy = if (y1 < y2) 1 else -1
            var err = dx + dy
            while (true) {
                pts.add(Point(x, y))
                if (x == x2 && y == y2) break
                val e2 = 2 * err
                if (e2 >= dy) { err += dy; x += sx }
                if (e2 <= dx) { err += dx; y += sy }
            }
            return pts
        }
    }
}

object DungeonGenerator {
    fun generate(dice: Dice, width: Int = 48, height: Int = 32, maxRooms: Int = 14): Dungeon {
        val dungeon = Dungeon(width, height)
        var attempts = 0
        while (dungeon.rooms.size < maxRooms && attempts < 300) {
            attempts++
            val w = dice.roll(6) + 3
            val h = dice.roll(4) + 3
            val x = dice.roll(width - w - 2)
            val y = dice.roll(height - h - 2)
            val room = Room(x, y, w, h)
            if (dungeon.rooms.any { it.intersects(room) }) continue
            for (ry in y until y + h) for (rx in x until x + w) dungeon[rx, ry] = Tile.FLOOR
            dungeon.rooms.lastOrNull()?.let { carveCorridor(dungeon, it.center, room.center, dice) }
            dungeon.rooms += room
        }
        // A few extra loops so the map isn't a single chain.
        repeat(dungeon.rooms.size / 4) {
            carveCorridor(dungeon, dice.pick(dungeon.rooms).center, dice.pick(dungeon.rooms).center, dice)
        }
        dungeon.start = dungeon.rooms.first().center
        // Stairs go in the room farthest (by walking) from the start.
        val farthest = dungeon.rooms.drop(1).maxByOrNull { r ->
            dungeon.findPath(dungeon.start.x, dungeon.start.y, r.center.x, r.center.y)?.size ?: 0
        } ?: dungeon.rooms.first()
        dungeon.stairs = farthest.center
        dungeon[farthest.center.x, farthest.center.y] = Tile.STAIRS
        return dungeon
    }

    private fun carveCorridor(d: Dungeon, a: Point, b: Point, dice: Dice) {
        if (dice.chance(1, 2)) {
            carveH(d, a.x, b.x, a.y); carveV(d, a.y, b.y, b.x)
        } else {
            carveV(d, a.y, b.y, a.x); carveH(d, a.x, b.x, b.y)
        }
    }

    private fun carveH(d: Dungeon, x1: Int, x2: Int, y: Int) {
        for (x in minOf(x1, x2)..maxOf(x1, x2)) if (d[x, y] == Tile.WALL) d[x, y] = Tile.FLOOR
    }

    private fun carveV(d: Dungeon, y1: Int, y2: Int, x: Int) {
        for (y in minOf(y1, y2)..maxOf(y1, y2)) if (d[x, y] == Tile.WALL) d[x, y] = Tile.FLOOR
    }
}
