class_name Dungeon
extends RefCounted
## A dungeon level: tiles, rooms, floor items, and field-of-view / pathfinding helpers.

enum Tile {WALL, FLOOR, STAIRS}

const DIRS: Array[Vector2i] = [
	Vector2i(1, 0), Vector2i(-1, 0), Vector2i(0, 1), Vector2i(0, -1),
	Vector2i(1, 1), Vector2i(1, -1), Vector2i(-1, 1), Vector2i(-1, -1),
]

var width: int
var height: int
var tiles := PackedByteArray()
var explored := PackedByteArray()
var visible := PackedByteArray()
## Rooms as Rect2i.
var rooms: Array[Rect2i] = []
## Floor items: {"x", "y", "kind": CHEST/POTION/GOLD/TREASURE, "item": magic item or null}.
var items: Array = []
var start := Vector2i(1, 1)
var stairs := Vector2i(1, 1)


func _init(w: int, h: int) -> void:
	width = w
	height = h
	tiles.resize(w * h)
	tiles.fill(Tile.WALL)
	explored.resize(w * h)
	visible.resize(w * h)


static func walkable(t: int) -> bool:
	return t != Tile.WALL


static func transparent(t: int) -> bool:
	return t != Tile.WALL


static func room_center(r: Rect2i) -> Vector2i:
	return Vector2i(r.position.x + r.size.x / 2, r.position.y + r.size.y / 2)


static func rooms_intersect(a: Rect2i, b: Rect2i, margin: int = 1) -> bool:
	return a.position.x - margin < b.end.x and a.end.x + margin > b.position.x and \
		a.position.y - margin < b.end.y and a.end.y + margin > b.position.y


static func make_item(ix: int, iy: int, kind: String, magic = null) -> Dictionary:
	return {"x": ix, "y": iy, "kind": kind, "item": magic}


func in_bounds(x: int, y: int) -> bool:
	return x >= 0 and x < width and y >= 0 and y < height


func at(x: int, y: int) -> int:
	return tiles[y * width + x] if in_bounds(x, y) else Tile.WALL


func set_tile(x: int, y: int, t: int) -> void:
	if in_bounds(x, y):
		tiles[y * width + x] = t


func is_walkable(x: int, y: int) -> bool:
	return walkable(at(x, y))


func is_visible(x: int, y: int) -> bool:
	return in_bounds(x, y) and visible[y * width + x] != 0


func is_explored(x: int, y: int) -> bool:
	return in_bounds(x, y) and explored[y * width + x] != 0


func item_at(x: int, y: int):
	for it in items:
		if it["x"] == x and it["y"] == y:
			return it
	return null


## Recomputes the visible set as the union of what each [x, y, radius] viewer sees.
func compute_fov(viewers: Array) -> void:
	visible.fill(0)
	for v in viewers:
		_cast_fov(v[0], v[1], v[2])


func _cast_fov(ox: int, oy: int, radius: int) -> void:
	var r2 := radius * radius + radius
	for tx in range(ox - radius, ox + radius + 1):
		for ty in range(oy - radius, oy + radius + 1):
			var on_edge := tx == ox - radius or tx == ox + radius or ty == oy - radius or ty == oy + radius
			if not on_edge:
				continue
			for p in line(ox, oy, tx, ty):
				if not in_bounds(p.x, p.y):
					break
				var dx := p.x - ox
				var dy := p.y - oy
				if dx * dx + dy * dy > r2:
					break
				visible[p.y * width + p.x] = 1
				explored[p.y * width + p.x] = 1
				if not transparent(at(p.x, p.y)):
					break


## True if nothing opaque lies strictly between the two squares.
func has_line_of_sight(x1: int, y1: int, x2: int, y2: int) -> bool:
	var pts := line(x1, y1, x2, y2)
	for i in range(1, pts.size() - 1):
		if not transparent(at(pts[i].x, pts[i].y)):
			return false
	return true


## Breadth-first search with 8-way movement (no cutting corners around walls).
## Returns the steps from start (exclusive) to goal (inclusive), or null.
## [param blocked] is a Callable(x, y) -> bool; if [param stop_adjacent], the path ends next to the goal.
func find_path(sx: int, sy: int, gx: int, gy: int, blocked: Callable = Callable(), stop_adjacent: bool = false, max_nodes: int = -1):
	if sx == gx and sy == gy:
		return []
	if stop_adjacent and Creature.chebyshev(sx, sy, gx, gy) == 1:
		return []
	if max_nodes < 0:
		max_nodes = width * height
	var has_blocked := blocked.is_valid()
	var came := PackedInt32Array()
	came.resize(width * height)
	came.fill(-1)
	var start_idx := sy * width + sx
	came[start_idx] = start_idx
	var queue := PackedInt32Array([start_idx])
	var head := 0
	var visited := 0
	while head < queue.size() and visited < max_nodes:
		var cur := queue[head]
		head += 1
		visited += 1
		var cx := cur % width
		var cy := cur / width
		for d in DIRS:
			var nx := cx + d.x
			var ny := cy + d.y
			if not in_bounds(nx, ny):
				continue
			var ni := ny * width + nx
			if came[ni] != -1:
				continue
			if not is_walkable(nx, ny):
				continue
			if d.x != 0 and d.y != 0 and (not is_walkable(cx + d.x, cy) or not is_walkable(cx, cy + d.y)):
				continue
			var is_goal := nx == gx and ny == gy
			var is_blocked: bool = has_blocked and blocked.call(nx, ny)
			var reached: bool = (Creature.chebyshev(nx, ny, gx, gy) == 1 and not is_blocked) if stop_adjacent else is_goal
			if not is_goal and is_blocked:
				continue
			if stop_adjacent and is_goal:
				continue
			came[ni] = cur
			if reached:
				var path: Array[Vector2i] = []
				var p := ni
				while p != start_idx:
					path.append(Vector2i(p % width, p / width))
					p = came[p]
				path.reverse()
				return path
			queue.append(ni)
	return null


func can_step(fx: int, fy: int, tx: int, ty: int) -> bool:
	if Creature.chebyshev(fx, fy, tx, ty) != 1 or not is_walkable(tx, ty):
		return false
	var dx := tx - fx
	var dy := ty - fy
	return dx == 0 or dy == 0 or (is_walkable(fx + dx, fy) and is_walkable(fx, fy + dy))


## Bresenham line including both endpoints.
static func line(x1: int, y1: int, x2: int, y2: int) -> Array[Vector2i]:
	var pts: Array[Vector2i] = []
	var x := x1
	var y := y1
	var dx := absi(x2 - x1)
	var dy := -absi(y2 - y1)
	var sx := 1 if x1 < x2 else -1
	var sy := 1 if y1 < y2 else -1
	var err := dx + dy
	while true:
		pts.append(Vector2i(x, y))
		if x == x2 and y == y2:
			break
		var e2 := 2 * err
		if e2 >= dy:
			err += dy
			x += sx
		if e2 <= dx:
			err += dx
			y += sy
	return pts


# --- Generation ---------------------------------------------------------------

static func generate(dice: Dice, w: int = 48, h: int = 32, max_rooms: int = 14) -> Dungeon:
	var d := Dungeon.new(w, h)
	var attempts := 0
	while d.rooms.size() < max_rooms and attempts < 300:
		attempts += 1
		var rw := dice.roll(6) + 3
		var rh := dice.roll(4) + 3
		var rx := dice.roll(w - rw - 2)
		var ry := dice.roll(h - rh - 2)
		var room := Rect2i(rx, ry, rw, rh)
		var clash := false
		for r in d.rooms:
			if rooms_intersect(r, room):
				clash = true
				break
		if clash:
			continue
		for yy in range(ry, ry + rh):
			for xx in range(rx, rx + rw):
				d.set_tile(xx, yy, Tile.FLOOR)
		if not d.rooms.is_empty():
			_carve_corridor(d, room_center(d.rooms.back()), room_center(room), dice)
		d.rooms.append(room)
	# A few extra loops so the map isn't a single chain.
	for i in d.rooms.size() / 4:
		_carve_corridor(d, room_center(dice.pick(d.rooms)), room_center(dice.pick(d.rooms)), dice)
	d.start = room_center(d.rooms[0])
	# Stairs go in the room farthest (by walking) from the start.
	var farthest: Rect2i = d.rooms[0]
	var best := -1
	for i in range(1, d.rooms.size()):
		var c := room_center(d.rooms[i])
		var p = d.find_path(d.start.x, d.start.y, c.x, c.y)
		var n: int = p.size() if p != null else 0
		if n > best:
			best = n
			farthest = d.rooms[i]
	d.stairs = room_center(farthest)
	d.set_tile(d.stairs.x, d.stairs.y, Tile.STAIRS)
	return d


static func _carve_corridor(d: Dungeon, a: Vector2i, b: Vector2i, dice: Dice) -> void:
	if dice.chance(1, 2):
		_carve_h(d, a.x, b.x, a.y)
		_carve_v(d, a.y, b.y, b.x)
	else:
		_carve_v(d, a.y, b.y, a.x)
		_carve_h(d, a.x, b.x, b.y)


static func _carve_h(d: Dungeon, x1: int, x2: int, y: int) -> void:
	for x in range(mini(x1, x2), maxi(x1, x2) + 1):
		if d.at(x, y) == Tile.WALL:
			d.set_tile(x, y, Tile.FLOOR)


static func _carve_v(d: Dungeon, y1: int, y2: int, x: int) -> void:
	for y in range(mini(y1, y2), maxi(y1, y2) + 1):
		if d.at(x, y) == Tile.WALL:
			d.set_tile(x, y, Tile.FLOOR)
