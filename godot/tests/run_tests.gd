extends SceneTree
## Headless test runner: godot --headless --path godot --script res://tests/run_tests.gd
## Runs every test_* method of the suites below, then boots the real main scene and lets a bot play
## through the UI for a while. Exits non-zero on failure.

const SUITES := [
	"res://tests/test_smoke.gd",
	"res://tests/test_rules.gd",
	"res://tests/test_dungeon.gd",
	"res://tests/test_game.gd",
	"res://tests/test_save.gd",
]
const UI_FRAMES := 900

var failures: Array[String] = []
var current := ""
var _main: Node
var _frame := 0
var _bot


func _initialize() -> void:
	var total := 0
	for path in SUITES:
		var suite = load(path).new()
		suite.runner = self
		for m in suite.get_method_list():
			var name: String = m["name"]
			if not name.begins_with("test_"):
				continue
			current = "%s.%s" % [path.get_file().get_basename(), name]
			total += 1
			var before := failures.size()
			suite.call(name)
			print("%s %s" % ["PASS" if failures.size() == before else "FAIL", current])
	print("\n%d tests, %d failures" % [total, failures.size()])
	# UI smoke run: the main scene, driven through its own tap handling.
	current = "ui_smoke"
	Session.dice = Dice.new(11)
	Session.new_party()
	_main = load("res://scenes/main.tscn").instantiate()
	root.add_child(_main)
	_bot = load("res://tests/bot.gd").new()


func _process(_delta: float) -> bool:
	_frame += 1
	var ui: GameUI = _main.ui
	if _frame == 3:
		# Press "Begin Adventure" via the hit regions the party screen drew.
		ui._handle_tap(Vector2(ui.size.x * 0.85, ui.size.y * 0.92))
		# The headless renderer never calls _draw(), so there may be no hit regions to tap.
		if Session.game == null:
			ui._begin_adventure()
	elif _frame > 3 and Session.game != null:
		var g: Game = Session.game
		# Headless frames run faster than real time, so advance the game every frame too.
		if not ui._paused():
			if not g.is_busy() and g.phase != Game.Phase.GAME_OVER and _frame % 2 == 0:
				_bot.decide(g)
			g.tick()
		if _frame % 100 == 0 and _frame != 500:
			ui._open_menu()
		elif _frame % 100 == 10:
			ui.handle_back()
		if _frame == 505:
			_check_gestures(ui)
	if _frame >= UI_FRAMES:
		var window: Vector2 = _main.get_viewport().get_visible_rect().size
		if ui.size != window:
			fail("UI size %s doesn't match the window %s" % [ui.size, window])
		if Session.game == null:
			fail("Begin Adventure did not start a game")
		elif ui.screen != GameUI.Screen.PLAY:
			fail("UI is not on the play screen")
		else:
			print("ui_smoke: depth %d, %d log lines" % [Session.game.depth, Session.game.log_entries.size()])
		print("%s ui_smoke" % ("PASS" if failures.is_empty() else "FAIL"))
		for f in failures:
			printerr(f)
		quit(1 if failures.size() > 0 else 0)
	return false


## Feeds touch events through the UI: a drag pans, a squeeze zooms in, a spread zooms out,
## and a short tap is still a tap (not a pan).
func _check_gestures(ui: GameUI) -> void:
	var world: DungeonView = ui.world
	ui._menu_open = false
	ui._sheet_hero = null
	ui._level_up_hero = null
	ui._inventory_open = false
	var p := Vector2(ui.map_w * 0.4, ui.size.y * 0.5)
	ui._input(_touch(0, p, true))
	ui._input(_drag(0, p + Vector2(60, 0), Vector2(60, 0)))
	ui._input(_drag(0, p + Vector2(120, 0), Vector2(60, 0)))
	ui._input(_touch(0, p + Vector2(120, 0), false))
	if world.pan_offset.length() < 0.01:
		fail("dragging did not pan the camera")
	var before := world.zoom
	ui._input(_touch(0, p - Vector2(100, 0), true))
	ui._input(_touch(1, p + Vector2(100, 0), true))
	ui._input(_drag(1, p + Vector2(40, 0), Vector2(-60, 0)))
	ui._input(_touch(1, p + Vector2(40, 0), false))
	ui._input(_touch(0, p - Vector2(100, 0), false))
	if not world.zoom < before:
		fail("squeezing did not zoom in (%s -> %s)" % [before, world.zoom])
	before = world.zoom
	ui._input(_touch(0, p - Vector2(40, 0), true))
	ui._input(_touch(1, p + Vector2(40, 0), true))
	ui._input(_drag(1, p + Vector2(140, 0), Vector2(100, 0)))
	ui._input(_touch(1, p + Vector2(140, 0), false))
	ui._input(_touch(0, p - Vector2(40, 0), false))
	if not world.zoom > before:
		fail("spreading did not zoom out (%s -> %s)" % [before, world.zoom])
	var pan := world.pan_offset
	ui._input(_touch(0, p, true))
	ui._input(_touch(0, p + Vector2(2, 1), false))
	if world.pan_offset != pan:
		fail("a tap moved the camera")
	print("gestures: zoom %.2f, pan %s" % [world.zoom, world.pan_offset])


static func _touch(index: int, pos: Vector2, pressed: bool) -> InputEventScreenTouch:
	var e := InputEventScreenTouch.new()
	e.index = index
	e.position = pos
	e.pressed = pressed
	return e


static func _drag(index: int, pos: Vector2, rel: Vector2) -> InputEventScreenDrag:
	var e := InputEventScreenDrag.new()
	e.index = index
	e.position = pos
	e.relative = rel
	return e


func fail(message: String) -> void:
	failures.append("%s: %s" % [current, message])
