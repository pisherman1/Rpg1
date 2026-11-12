extends SceneTree
## Renders the real game with a real GPU (or lavapipe) and saves screenshots for visual review:
##   godot --path godot --script res://tests/screenshot.gd -- --out=/some/dir
## Shots: party screen, exploring, combat, character sheet, level-up panel.

var _main: Node
var _frame := 0
var _bot
var _out := "user://shots"
var _taken := {}
var _busy := false


func _initialize() -> void:
	for a in OS.get_cmdline_user_args():
		if a.begins_with("--out="):
			_out = a.substr(6)
	DirAccess.make_dir_recursive_absolute(_out)
	Session.dice = Dice.new(5)
	Session.new_party()
	_main = load("res://scenes/main.tscn").instantiate()
	root.add_child(_main)
	_bot = load("res://tests/bot.gd").new()


func _shot(label: String) -> void:
	_busy = true
	_taken[label] = true
	await RenderingServer.frame_post_draw
	var img := root.get_texture().get_image()
	var path := "%s/%s.jpg" % [_out, label]
	img.save_jpg(path, 0.88)
	var g: Game = Session.game
	print("SHOT %s -> %s (game=%s, phase=%s)" % [label, path, g != null, Game.Phase.keys()[g.phase] if g != null else "-"])
	_busy = false


func _process(_delta: float) -> bool:
	if _busy:
		return false
	_frame += 1
	var ui: GameUI = _main.ui
	if _frame == 40:
		_shot("1_party")
		return false
	if _frame == 45:
		ui._handle_tap(Vector2(ui.size.x * 0.85, ui.size.y * 0.92))
		if Session.game == null:
			ui._begin_adventure()
	var g: Game = Session.game
	if g == null:
		return false
	if _frame == 90 and not _taken.has("2_explore"):
		_shot("2_explore")
		return false
	if _frame > 90:
		# Let the bot play; the game is ticked by the UI's own timer as well.
		if not g.is_busy() and g.phase != Game.Phase.GAME_OVER and _frame % 4 == 0:
			_bot.decide(g)
		if _frame % 2 == 0:
			g.tick()
	if g.in_combat() and not _taken.has("3_combat") and _frame > 120:
		if _taken.has("_combat_seen") and _frame - int(_taken["_combat_seen"]) > 25:
			_shot("3_combat")
			return false
		if not _taken.has("_combat_seen"):
			_taken["_combat_seen"] = _frame
	if _taken.has("3_combat") and not _taken.has("4_sheet"):
		ui._sheet_hero = g.party[0]
		if _frame - int(_taken.get("_sheet_open", _frame)) > 3:
			_shot("4_sheet")
			ui._sheet_hero = null
			return false
		_taken["_sheet_open"] = _taken.get("_sheet_open", _frame)
	if _taken.has("4_sheet") and not _taken.has("5_levelup"):
		ui._open_level_up(g.party[2])
		if _frame - int(_taken.get("_lu_open", _frame)) > 3:
			_shot("5_levelup")
			ui._level_up_hero = null
			return false
		_taken["_lu_open"] = _taken.get("_lu_open", _frame)
	if _taken.has("5_levelup") or _frame > 3000:
		if not _taken.has("6_final"):
			_shot("6_final")
			return false
		quit(0)
	return false
