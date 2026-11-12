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
	# Overlays: open, wait a few frames, capture, then close on a later frame.
	if _taken.has("3_combat") and not _taken.has("4_sheet"):
		if not _taken.has("_sheet_open"):
			_taken["_sheet_open"] = _frame
			ui._sheet_hero = g.party[0]
		elif _frame - int(_taken["_sheet_open"]) > 4:
			_shot("4_sheet")
		return false
	if _taken.has("4_sheet") and not _taken.has("5_levelup"):
		if not _taken.has("_lu_open"):
			ui._sheet_hero = null
			_taken["_lu_open"] = _frame
			ui._open_level_up(g.party[2])
		elif _frame - int(_taken["_lu_open"]) > 4:
			_shot("5_levelup")
		return false
	ui._level_up_hero = null
	if _taken.has("5_levelup") or _frame > 3000:
		if not _taken.has("6_final"):
			_shot("6_final")
			return false
		if not _taken.has("_gallery"):
			_taken["_gallery"] = _frame
			_show_gallery()
			return false
		if not _taken.has("7_gallery") and _frame - int(_taken["_gallery"]) > 6:
			_shot("7_gallery")
			return false
		if _taken.has("7_gallery") and not _taken.has("_faces"):
			_taken["_faces"] = _frame
			_show_faces()
			return false
		if _taken.has("_faces") and not _taken.has("8_faces") and _frame - int(_taken["_faces"]) > 6:
			_shot("8_faces")
			return false
		if _taken.has("8_faces"):
			quit(0)
	return false


## Every monster and hero variant on one screen, for reviewing the models.
func _show_gallery() -> void:
	var studio: PortraitStudio = _main.ui.studio
	var layer := CanvasLayer.new()
	layer.layer = 50
	root.add_child(layer)
	var bg := ColorRect.new()
	bg.color = Color(0.1, 0.09, 0.13)
	bg.size = Vector2(1600, 720)
	layer.add_child(bg)
	var textures: Array = []
	for t in Rules.MONSTERS:
		textures.append(studio.monster_texture(t))
	for race in Rules.RACE_ORDER:
		for cls in Rules.CLASS_ORDER:
			for g in ["MALE", "FEMALE"]:
				textures.append(studio.hero_texture(race, cls, g))
	var cols := 17
	var w := 1600.0 / cols
	for i in textures.size():
		var r := TextureRect.new()
		r.texture = textures[i]
		r.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		r.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		r.position = Vector2((i % cols) * w, (i / cols) * 120.0)
		r.size = Vector2(w, 120)
		layer.add_child(r)


## Close-ups of the faces that carry extra parts (fox masks, snouts, tusks, ears, beards).
func _show_faces() -> void:
	var studio: PortraitStudio = _main.ui.studio
	var layer := CanvasLayer.new()
	layer.layer = 60
	root.add_child(layer)
	var bg := ColorRect.new()
	bg.color = Color(0.14, 0.12, 0.18)
	bg.size = Vector2(1600, 720)
	layer.add_child(bg)
	var textures: Array = []
	for cls in Rules.CLASS_ORDER:
		textures.append(studio.hero_texture("THERIAN", cls, "MALE"))
		textures.append(studio.hero_texture("THERIAN", cls, "FEMALE"))
	for t in ["KOBOLD", "GNOLL", "GOBLIN", "ORC", "OGRE"]:
		textures.append(studio.monster_texture(t))
	textures.append(studio.hero_texture("ELF", "FIGHTER", "FEMALE"))
	textures.append(studio.hero_texture("DWARF", "CLERIC", "MALE"))
	textures.append(studio.hero_texture("DWARF", "WIZARD", "FEMALE"))
	for i in textures.size():
		var r := TextureRect.new()
		r.texture = textures[i]
		r.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		r.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		r.position = Vector2((i % 8) * 200.0, (i / 8) * 360.0)
		r.size = Vector2(200, 350)
		layer.add_child(r)
