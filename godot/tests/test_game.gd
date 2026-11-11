extends "res://tests/test_base.gd"

const Bot := preload("res://tests/bot.gd")


func _new_game(seed: int, classes: Array = Rules.CLASS_ORDER, races: Array = Rules.RACE_ORDER) -> Game:
	var dice := Dice.new(seed)
	var party := []
	for i in Party.SIZE:
		var scores := []
		for j in 6:
			scores.append(dice.ability_score())
		party.append(Hero.create("H%d" % i, races[i % races.size()], classes[i % classes.size()], scores))
	return Game.new(party, dice)


func test_party_starts_together_on_distinct_squares() -> void:
	for seed in 20:
		var game := _new_game(seed)
		var squares := {}
		for h in game.party:
			squares[Vector2i(h.x, h.y)] = true
			check(h.distance_to(game.leader()) <= 3)
		check_eq(squares.size(), 4, "seed %d" % seed)


func test_followers_keep_up_with_the_leader() -> void:
	var game := _new_game(3)
	var bot := Bot.new()
	for i in 300:
		if game.phase != Game.Phase.EXPLORING:
			return
		if not game.is_busy():
			var f = bot.nearest_frontier(game)
			if f != null:
				game.tap(f.x, f.y)
		game.tick()
		var squares := {}
		for h in game.living_heroes():
			squares[Vector2i(h.x, h.y)] = true
			check(game.dungeon.is_walkable(h.x, h.y))
		check_eq(squares.size(), game.living_heroes().size(), "heroes overlap")


func test_bot_plays_many_parties_without_crashing_or_stalling() -> void:
	var deepest := 0
	var level_ups := 0
	for seed in range(1, 13):
		var classes := []
		for i in 4:
			classes.append(Rules.CLASS_ORDER[(seed + i * (seed % 3 + 1)) % 4])
		var game := _new_game(seed, classes)
		var bot := Bot.new()
		bot.play(game, 20000)
		check_eq(bot.stuck_report, "", "seed %d" % seed)
		deepest = maxi(deepest, game.depth)
		level_ups += game.party.filter(func(h): return h.level > 1).size()
		for h in game.party:
			check(h.hp >= 0 and h.hp <= h.get_max_hp(), "%s hp %d/%d" % [h.name, h.hp, h.get_max_hp()])
			if h.dead:
				check_eq(h.hp, 0)
		if game.phase == Game.Phase.GAME_OVER:
			check(game.conscious_heroes().is_empty())
	check(deepest >= 3, "deepest depth reached was %d" % deepest)
	check(level_ups > 0, "nobody levelled up")


func test_seeing_a_monster_starts_combat_with_everyone_in_initiative() -> void:
	var game := _new_game(5)
	var bot := Bot.new()
	var ticks := 0
	while game.phase == Game.Phase.EXPLORING and ticks < 20000 and game.depth == 1:
		ticks += 1
		if not game.is_busy():
			var goal = bot.nearest_frontier(game)
			if goal == null:
				goal = game.dungeon.stairs
			game.tap(goal.x, goal.y)
		game.tick()
	check(game.in_combat())
	for h in game.living_heroes():
		check(game.turn_order.has(h))
	check(game.turn_order.any(func(c): return c is Monster))
	check(game.log_entries.any(func(e): return e["text"].begins_with("Initiative:")))


func test_portrait_tap_changes_leader_outside_combat() -> void:
	var game := _new_game(2)
	game.select_hero(2)
	check_eq(game.leader_index, 2)
	check(game.active_hero() == game.party[2])


func test_potion_can_be_given_to_anyone_outside_combat() -> void:
	var game := _new_game(4)
	var patient: Hero = game.party[3]
	patient.hp = 1
	var potion: Dictionary = game.available_actions().filter(func(a): return a["id"] == "POTION")[0]
	var before := game.total_potions()
	game.perform(potion)
	game.select_hero(3)
	check(patient.hp > 1)
	check_eq(game.total_potions(), before - 1)


func test_long_rest_once_per_depth() -> void:
	var game := _new_game(6)
	var find := func(): return game.available_actions().filter(func(a): return a["id"] == "LONG_REST")[0]
	game.perform(find.call())
	check_eq(find.call()["enabled"], false)
