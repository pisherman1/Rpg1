extends SceneTree
## Renders the party screen and a few seconds of play, then prints small JPEG screenshots as base64
## between markers so CI logs can be checked visually. Needs a real renderer (e.g. under xvfb).

var _main: Node
var _frame := 0
var _bot


func _initialize() -> void:
	Session.dice = Dice.new(5)
	Session.new_party()
	_main = load("res://scenes/main.tscn").instantiate()
	root.add_child(_main)
	_bot = load("res://tests/bot.gd").new()


func _shot(label: String) -> void:
	var img := root.get_texture().get_image()
	img.resize(img.get_width() / 2, img.get_height() / 2, Image.INTERPOLATE_BILINEAR)
	print("SHOT-BEGIN %s" % label)
	print(Marshalls.raw_to_base64(img.save_jpg_to_buffer(0.6)))
	print("SHOT-END")


func _process(_delta: float) -> bool:
	_frame += 1
	var ui: GameUI = _main.ui
	if _frame == 20:
		_shot("party")
		ui._handle_tap(Vector2(ui.size.x * 0.85, ui.size.y * 0.92))
	elif _frame > 20 and Session.game != null:
		var g: Game = Session.game
		# Explore until the first fight, then let it play out a little.
		if not g.is_busy() and g.phase != Game.Phase.GAME_OVER and _frame % 3 == 0 and (g.phase == Game.Phase.EXPLORING or _frame % 9 == 0):
			_bot.decide(g)
		g.tick()
		if _frame == 400 or (g.in_combat() and _frame % 40 == 0 and _frame > 120):
			_shot("play-%d" % _frame)
			if _frame > 120:
				quit(0)
	if _frame > 2000:
		_shot("final")
		quit(0)
	return false
