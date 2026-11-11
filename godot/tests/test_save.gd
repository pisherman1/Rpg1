extends "res://tests/test_base.gd"

const Bot := preload("res://tests/bot.gd")


func _new_game(seed: int) -> Game:
	var b := Party.Builder.new(Dice.new(seed))
	return Game.new(b.build(), Dice.new(seed * 7))


## Everything except the log, which gains a "Game loaded." line on load.
func _state(g: Game) -> String:
	var s := g.to_save()
	s.erase("log")
	return JSON.stringify(s)


func _check_round_trip(g: Game) -> Game:
	var loaded := Game.from_json(g.to_json())
	check(loaded != null, "load failed: %s" % Game.last_error)
	if loaded == null:
		return null
	check_eq(_state(loaded), _state(g), "state")
	check_eq(loaded.log_entries.back()["text"], "Game loaded.")
	var a := g.active_hero()
	var b := loaded.active_hero()
	check_eq(b.name if b != null else "", a.name if a != null else "")
	check_eq(str(loaded.available_actions()), str(g.available_actions()))
	check_eq(loaded.prompt(), g.prompt())
	check_eq(loaded.dungeon.visible, g.dungeon.visible, "visibility")
	return loaded


func test_fresh_game_round_trips() -> void:
	_check_round_trip(_new_game(1))


func test_games_round_trip_at_many_points_and_keep_playing() -> void:
	var saw_combat := false
	var saw_monster_turn := false
	for seed in range(1, 7):
		var g := _new_game(seed)
		var bot := Bot.new()
		for i in 10:
			bot.play(g, 400 + seed * 37)
			if g.in_combat():
				saw_combat = true
			if g.phase == Game.Phase.MONSTER_TURN:
				saw_monster_turn = true
			_check_round_trip(g)
		var loaded := Game.from_json(g.to_json())
		var bot2 := Bot.new()
		bot2.play(loaded, 3000)
		check_eq(bot2.stuck_report, "")
	check(saw_combat, "never saved during combat")
	check(saw_monster_turn, "never saved during a monster turn")


func test_bad_saves_are_rejected() -> void:
	check_eq(Game.from_json("[]"), null)
	check_eq(Game.from_json("{\"version\":999}"), null)
	var text := _new_game(2).to_json()
	check_eq(Game.from_json(text.replace("\"tiles\":\"", "\"tiles\":\"WW")), null)
	check_eq(Game.from_json(text.substr(0, text.length() / 2)), null)
