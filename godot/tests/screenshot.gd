extends SceneTree
## Renders the party screen and a few seconds of play, then prints small JPEG screenshots as base64
## between markers so CI logs can be checked visually. Needs a real renderer (e.g. under xvfb).

var _main: Node
var _frame := 0
var _bot
var _done := false


func _initialize() -> void:
	Session.dice = Dice.new(5)
	Session.new_party()
	_main = load("res://scenes/main.tscn").instantiate()
	root.add_child(_main)
	_bot = load("res://tests/bot.gd").new()


func _shot(label: String, then_quit: bool = false) -> void:
	await RenderingServer.frame_post_draw
	var ui: GameUI = _main.ui
	print("ui size=%s pos=%s hits=%d visible=%s world=%s" % [ui.size, ui.global_position, ui._hits.size(), ui.is_visible_in_tree(), ui.world.size])
	print("renderer=%s size=%s game=%s" % [RenderingServer.get_video_adapter_name(), root.size, Session.game != null])
	var img := root.get_texture().get_image()
	print("SHOT %s: %s" % [label, _stats(img)])
	if then_quit:
		quit(0)


func _process(_delta: float) -> bool:
	if _done:
		return false
	_frame += 1
	var ui: GameUI = _main.ui
	if _frame == 20:
		_shot("party")
	elif _frame == 26:
		print("tap: ui size=%s hits=%d" % [ui.size, ui._hits.size()])
		ui._handle_tap(Vector2(ui.size.x * 0.85, ui.size.y * 0.92))
	elif _frame > 26 and Session.game != null:
		var g: Game = Session.game
		# Explore until the first fight, then let it play out a little.
		if not g.is_busy() and g.phase != Game.Phase.GAME_OVER and _frame % 3 == 0 and (g.phase == Game.Phase.EXPLORING or _frame % 9 == 0):
			_bot.decide(g)
		g.tick()
		if _frame == 400 or (g.in_combat() and _frame % 40 == 0 and _frame > 120):
			_done = _frame > 120
			_shot("play-%d" % _frame, _done)
	if _frame > 2000:
		_done = true
		_shot("final", true)
	return false


## Mean brightness and number of distinct colours for the map area and the side panel.
func _stats(img: Image) -> String:
	var out := []
	var w := img.get_width()
	var h := img.get_height()
	for region in [["map", Rect2i(0, 0, int(w * 0.66), h)], ["panel", Rect2i(int(w * 0.66), 0, w - int(w * 0.66), h)]]:
		var r: Rect2i = region[1]
		var total := 0.0
		var colors := {}
		var n := 0
		for y in range(r.position.y, r.end.y, 6):
			for x in range(r.position.x, r.end.x, 6):
				var c := img.get_pixel(x, y)
				total += c.get_luminance()
				colors[c.to_rgba32() >> 12] = true
				n += 1
		out.append("%s lum=%.3f colors=%d" % [region[0], total / maxi(1, n), colors.size()])
	return ", ".join(out)
