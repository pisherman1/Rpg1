extends "res://tests/test_base.gd"


func test_every_room_and_the_stairs_are_reachable() -> void:
	for seed in 50:
		var d := Dungeon.generate(Dice.new(seed))
		check(d.rooms.size() >= 4, "seed %d made %d rooms" % [seed, d.rooms.size()])
		check_eq(d.at(d.stairs.x, d.stairs.y), Dungeon.Tile.STAIRS)
		check(d.find_path(d.start.x, d.start.y, d.stairs.x, d.stairs.y) != null, "seed %d stairs" % seed)
		for r in d.rooms:
			var c := Dungeon.room_center(r)
			check(d.find_path(d.start.x, d.start.y, c.x, c.y) != null)


func _open_room() -> Dungeon:
	var d := Dungeon.new(10, 10)
	for x in range(1, 9):
		for y in range(1, 9):
			d.set_tile(x, y, Dungeon.Tile.FLOOR)
	return d


func test_diagonal_moves_cost_one_square() -> void:
	var path = _open_room().find_path(1, 1, 5, 5)
	check_eq(path.size(), 4)
	check_eq(path.back(), Vector2i(5, 5))


func test_cannot_cut_corners_around_walls() -> void:
	var d := _open_room()
	d.set_tile(2, 1, Dungeon.Tile.WALL)
	check(not d.can_step(1, 1, 2, 2))
	check(d.can_step(1, 1, 1, 2))


func test_stop_adjacent_ends_next_to_goal() -> void:
	var path = _open_room().find_path(1, 1, 6, 1, Callable(), true)
	check_eq(path.back(), Vector2i(5, 1))


func test_blocked_squares_are_avoided() -> void:
	var d := _open_room()
	for y in range(1, 8):
		d.set_tile(4, y, Dungeon.Tile.WALL)
	check_eq(d.find_path(1, 1, 7, 1, func(x, y): return x == 4 and y == 8), null)


func test_walls_block_sight() -> void:
	var d := _open_room()
	for y in range(1, 9):
		d.set_tile(5, y, Dungeon.Tile.WALL)
	d.compute_fov([[2, 4, 8]])
	check(d.is_visible(4, 4))
	check(d.is_visible(5, 4))
	check(not d.is_visible(7, 4))
	check(not d.has_line_of_sight(2, 4, 7, 4))
	check(d.has_line_of_sight(2, 4, 4, 6))
