class_name GameUI
extends Control
## Draws and drives the whole game: the party builder and the dungeon UI over the world view.
## All rules live in the core scripts; this only renders state and forwards taps.

enum Screen {PARTY, PLAY}

const SPELLS := "Spells"

const C_BG := Color("0b0910")
const C_PANEL := Color("17121f")
const C_PANEL_LIGHT := Color("251d33")
const C_BUTTON := Color("33284a")
const C_BUTTON_ON := Color("9a6420")
const C_BUTTON_OFF := Color("1d1727")
const C_BORDER := Color("5a4a78")
const C_GOLD := Color("f2c641")
const C_TEXT := Color("ede6f5")
const C_DIM := Color("8c82a0")
const C_HP := Color("4cc650")
const C_HP_LOW := Color("e04040")
const C_DARK := Color("16121c")

const LOG_COLORS := {
	"INFO": Color("c8c0d8"), "ROLL": Color("9cd0ff"), "GOOD": Color("8ceb8c"),
	"BAD": Color("ff8c7a"), "LOOT": Color("f2c641"), "LEVEL": Color("e0a0ff"),
}

var world: DungeonView
var audio: GameAudio
var studio: PortraitStudio
var font: Font
var title_font: Font

var _styles := {}
## Button presses glow briefly: [rect, time].
var _press_rect := Rect2()
var _press_time := -10000
## Smoothed hit points for the party bars: hero -> [shown, trail].
var _hp_shown := {}

var screen := Screen.PARTY

# --- Layout & input -------------------------------------------------------------
var _hits: Array = []  # [Rect2, Callable, click]
var _down := Vector2.ZERO
var map_w := 0
var tile_px := 64

# --- Timing --------------------------------------------------------------------
var _last_tick := 0
var _now := 0

var _log_expanded := false
var _sheet_hero: Hero = null
var _premade_index := [-1, -1, -1, -1]
var _premade_party_index := 0

# --- Level-up, inventory and sub-menus -----------------------------------------
var _level_up_hero: Hero = null
var _asi_draft := {}
var _inventory_open := false
var _inv_hero_index := 0
var _inv_selected_item = null
var _inv_selected_slot := ""
## Open action sub-menu ("Spells" or an action group) and the hero it belongs to.
var _sub_menu := ""
var _sub_menu_for: Hero = null

## Level-up celebrations waiting to be shown, oldest first.
var _level_banners: Array = []
var _banner_start := 0

# --- Save/load menu --------------------------------------------------------------
var _menu_open := false
var _slot_infos := {}
var _pending_overwrite := -1
var _toast_text := ""
var _toast_until := 0

# Autosave when a level starts, a fight ends or the party falls.
var _last_depth := -1
var _last_phase := -1

var _name_dialog: AcceptDialog
var _name_edit: LineEdit
var _name_callback: Callable


func _ready() -> void:
	texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR
	mouse_filter = Control.MOUSE_FILTER_STOP
	font = load("res://assets/fonts/Inter-SemiBold.otf")
	title_font = load("res://assets/fonts/Caladea-Bold.ttf")
	screen = Screen.PLAY if Session.game != null else Screen.PARTY
	_name_dialog = AcceptDialog.new()
	_name_dialog.title = "Hero name"
	_name_edit = LineEdit.new()
	_name_edit.max_length = 16
	_name_edit.custom_minimum_size = Vector2(360, 0)
	_name_dialog.add_child(_name_edit)
	_name_dialog.register_text_enter(_name_edit)
	_name_dialog.confirmed.connect(func():
		var n := _name_edit.text.strip_edges()
		if n != "" and _name_callback.is_valid():
			_name_callback.call(n))
	add_child(_name_dialog)
	resized.connect(_on_resized)
	_on_resized()


func _on_resized() -> void:
	map_w = int(size.x * 0.66)
	tile_px = 16 * maxi(2, int(size.y) / (16 * 10))
	if world != null:
		world.map_fraction = 0.66


func _paused() -> bool:
	return _menu_open or _level_up_hero != null or _inventory_open


func _process(delta: float) -> void:
	_now = Time.get_ticks_msec()
	var g := Session.game
	if studio != null:
		studio.set_turntables_active(screen == Screen.PARTY)
	if screen == Screen.PLAY and g != null:
		if not _paused():
			var interval := 105
			if g.phase == Game.Phase.MONSTER_TURN:
				interval = 240
			elif g.in_combat():
				interval = 150
			if _now - _last_tick >= interval:
				_last_tick = _now
				g.tick()
			_check_autosave(g)
		world.game = g
		world.step(g.drain_fx(), 0.0 if _paused() else delta)
		for s in g.drain_sounds() + world.animator.drain_cues():
			audio.play(s)
		for n in g.drain_level_ups():
			if _level_banners.is_empty():
				_banner_start = _now
			_level_banners.append(n)
		if g.phase == Game.Phase.GAME_OVER:
			audio.set_music("DEFEAT")
		elif g.in_combat():
			audio.set_music("COMBAT")
		else:
			audio.set_music("EXPLORE")
	else:
		world.game = null
		world.step([], delta)
		audio.set_music("TITLE")
	queue_redraw()


func _check_autosave(g: Game) -> void:
	var combat_ended := _last_phase != -1 and _last_phase != Game.Phase.EXPLORING and g.phase == Game.Phase.EXPLORING
	var fell := g.phase == Game.Phase.GAME_OVER and _last_phase != Game.Phase.GAME_OVER
	if (_last_depth != -1 and g.depth != _last_depth) or combat_ended or fell:
		Session.autosave()
	_last_depth = g.depth
	_last_phase = g.phase


func _toast(message: String) -> void:
	_toast_text = message
	_toast_until = _now + 2500


func _refresh_slots() -> void:
	_slot_infos = {}
	for slot in [Session.AUTOSAVE] + Session.MANUAL_SLOTS:
		_slot_infos[slot] = Session.info(slot)


func _open_menu() -> void:
	_refresh_slots()
	_pending_overwrite = -1
	_menu_open = true


func _load_slot(slot: int) -> void:
	var err := Session.load_slot(slot)
	if err != "":
		_toast("Couldn't load: " + err)
		return
	_log_expanded = false
	_sheet_hero = null
	_menu_open = false
	_last_depth = -1
	_last_phase = -1
	screen = Screen.PLAY
	_toast("Game loaded")


## Returns true if the back press was consumed.
func handle_back() -> bool:
	if _menu_open:
		_menu_open = false
	elif _level_up_hero != null:
		_level_up_hero = null
	elif _inventory_open:
		_inventory_open = false
	elif _sheet_hero != null:
		_sheet_hero = null
	elif _log_expanded:
		_log_expanded = false
	else:
		return false
	return true


func _ask_name(current: String, callback: Callable) -> void:
	_name_callback = callback
	_name_edit.text = current
	_name_dialog.popup_centered()
	_name_edit.grab_focus()
	_name_edit.select_all()


# =================================================================================
# Input
# =================================================================================

func _gui_input(event: InputEvent) -> void:
	if event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT:
		if event.pressed:
			_down = event.position
		else:
			if absf(event.position.x - _down.x) > tile_px or absf(event.position.y - _down.y) > tile_px:
				return
			_handle_tap(event.position)
		accept_event()


func _handle_tap(p: Vector2) -> void:
	# Topmost regions are added last.
	for i in range(_hits.size() - 1, -1, -1):
		var h: Array = _hits[i]
		if h[0].has_point(p):
			if h[2]:
				audio.play("CLICK")
				_press_rect = h[0]
				_press_time = _now
			h[1].call()
			return
	var g := Session.game
	if g == null or screen != Screen.PLAY or p.x >= map_w:
		return
	var t := world.tile_at(p)
	g.tap(t.x, t.y)


func _hit(r: Rect2, action: Callable, click: bool = false) -> void:
	_hits.append([r, action, click])


# =================================================================================
# Drawing helpers
# =================================================================================

static func _r(l: float, t: float, r: float, b: float) -> Rect2:
	return Rect2(l, t, r - l, b - t)


func _text_width(s: String, sz: float) -> float:
	return font.get_string_size(s, HORIZONTAL_ALIGNMENT_LEFT, -1, maxi(1, int(sz))).x


## Draws [param s] with its baseline at y; [param align] is -1 left, 0 centre, 1 right.
func _text(s: String, x: float, y: float, sz: float, color: Color = C_TEXT, align: int = -1, f: Font = null) -> void:
	if f == null:
		f = font
	var fs := maxi(1, int(round(sz)))
	var w := f.get_string_size(s, HORIZONTAL_ALIGNMENT_LEFT, -1, fs).x
	var px := x
	if align == 0:
		px = x - w / 2
	elif align == 1:
		px = x - w
	draw_string(f, Vector2(px, y), s, HORIZONTAL_ALIGNMENT_LEFT, -1, fs, color)


## Headings in the serif title face, with a soft drop shadow.
func _title(s: String, x: float, y: float, sz: float, color: Color = C_GOLD, align: int = -1) -> void:
	_text(s, x + sz * 0.05, y + sz * 0.06, sz, Color(0, 0, 0, 0.6 * color.a), align, title_font)
	_text(s, x, y, sz, color, align, title_font)


## Draws [param s] shrunk to fit [param max_width].
func _fit_text(s: String, x: float, y: float, sz: float, max_width: float, color: Color = C_TEXT, align: int = -1) -> void:
	var w := _text_width(s, sz)
	_text(s, x, y, sz * max_width / w if w > max_width else sz, color, align)


## Splits [param s] into lines no wider than [param max_width] at [param sz].
func _wrap(s: String, sz: float, max_width: float) -> Array:
	var out := []
	var line := ""
	for word in s.split(" ", false):
		var trial: String = word if line == "" else line + " " + word
		if line != "" and _text_width(trial, sz) > max_width:
			out.append(line)
			line = word
		else:
			line = trial
	if line != "":
		out.append(line)
	return out


## A rounded panel style, cached by its look.
func _style(color: Color, border, border_width: float, radius: float, shadow: float, bevel: bool = false) -> StyleBoxFlat:
	var key := "%s|%s|%s|%s|%s|%s" % [color.to_html(), border.to_html() if border != null else "-", border_width, radius, shadow, bevel]
	if _styles.has(key):
		return _styles[key]
	var sb := StyleBoxFlat.new()
	sb.bg_color = color
	sb.set_corner_radius_all(int(radius))
	sb.anti_aliasing = true
	if border != null:
		sb.border_color = border
		sb.set_border_width_all(int(border_width))
		if bevel:
			sb.border_width_bottom = int(border_width) + 2
	if shadow > 0.0:
		sb.shadow_color = Color(0, 0, 0, 0.45)
		sb.shadow_size = int(shadow)
		sb.shadow_offset = Vector2(0, shadow * 0.4)
	_styles[key] = sb
	return sb


func _box(r: Rect2, color: Color, border = null, border_width: float = 3.0) -> void:
	var radius := clampf(minf(r.size.x, r.size.y) * 0.12, 3.0, 12.0)
	var big := r.size.x > size.x * 0.5 and r.size.y > size.y * 0.5
	draw_style_box(_style(color, border, border_width, radius, 10.0 if (border != null and big) else 0.0), r)


func _button(r: Rect2, label: String, action: Callable, enabled: bool = true, active: bool = false) -> void:
	var bg := C_BUTTON_ON if active else (C_BUTTON if enabled else C_BUTTON_OFF)
	var radius := clampf(r.size.y * 0.22, 4.0, 12.0)
	if active:
		# Pulsing golden glow around armed/primary buttons.
		var glow := 0.5 + 0.5 * sin(_now / 260.0)
		var halo := _style(Color(0, 0, 0, 0), null, 0, radius + 4, 0)
		var g: StyleBoxFlat = halo.duplicate()
		g.shadow_color = Color(1.0, 0.75, 0.25, 0.25 + 0.25 * glow)
		g.shadow_size = 10
		draw_style_box(g, r)
	var border: Color = C_GOLD if active else (C_BORDER if enabled else Color("2c2438"))
	draw_style_box(_style(bg, border, 2.0, radius, 3.0 if enabled else 0.0, true), r)
	# A soft gradient highlight makes the button read as raised.
	var hl := Rect2(r.position.x + radius * 0.6, r.position.y + 2.5, r.size.x - radius * 1.2, r.size.y * 0.5)
	var top := Color(1, 1, 1, 0.13 if enabled else 0.03)
	var bottom := Color(1, 1, 1, 0.0)
	draw_polygon(PackedVector2Array([hl.position, Vector2(hl.end.x, hl.position.y), hl.end, Vector2(hl.position.x, hl.end.y)]),
		PackedColorArray([top, top, bottom, bottom]))
	var pressed := r == _press_rect and _now - _press_time < 220
	if pressed:
		var k := 1.0 - (_now - _press_time) / 220.0
		draw_style_box(_style(Color(1, 0.9, 0.6, 0.35 * k), null, 0, radius, 0), r)
	_fit_text(label, r.get_center().x, r.get_center().y + r.size.y * 0.15, r.size.y * 0.38, r.size.x * 0.88,
		C_TEXT if enabled else C_DIM, 0)
	if enabled:
		_hit(r, action, true)


## Draws a portrait texture fitted (keeping its aspect) into the square at left/top of side sz.
func _portrait(tex: Texture2D, left: float, top: float, sz: float, color: Color = Color.WHITE, framed: bool = true) -> void:
	if framed:
		draw_style_box(_style(Color("0d0a14"), Color(C_BORDER, 0.8), 1.0, sz * 0.12, 0), Rect2(left, top, sz, sz))
	if tex == null:
		return
	var ts := tex.get_size()
	var scale := sz / maxf(ts.x, ts.y) * 1.08
	var w := ts.x * scale
	var h := ts.y * scale
	draw_texture_rect(tex, Rect2(left + (sz - w) / 2, top + sz - h, w, h), false, color)


func _hero_sprite(h: Hero, left: float, top: float, sz: float, color: Color = Color.WHITE) -> void:
	_portrait(studio.hero_texture(h.race, h.char_class, h.gender) if studio != null else null, left, top, sz, color)


func _creature_texture(c: Creature) -> Texture2D:
	return studio.creature_texture(c) if studio != null else null


func _draw() -> void:
	_hits.clear()
	# In play the world shows through the map area; everything else gets a solid background.
	if screen == Screen.PLAY and Session.game != null:
		draw_rect(_r(map_w, 0, size.x, size.y), C_BG)
	else:
		draw_polygon(PackedVector2Array([Vector2.ZERO, Vector2(size.x, 0), size, Vector2(0, size.y)]),
			PackedColorArray([Color("1d1530"), Color("1d1530"), Color("07060b"), Color("07060b")]))
	match screen:
		Screen.PARTY:
			_draw_party_screen()
		Screen.PLAY:
			if Session.game != null:
				_draw_play(Session.game)
			else:
				screen = Screen.PARTY
	if _menu_open:
		_draw_menu()
	if _now < _toast_until:
		_draw_toast()


# =================================================================================
# Party builder
# =================================================================================

func _draw_party_screen() -> void:
	if _slot_infos.is_empty():
		_refresh_slots()
	var w := size.x
	var h := size.y
	var builder := Session.builder
	var pad := h * 0.025
	_title("DUNGEON DELVER", w / 2, h * 0.095, h * 0.08, C_GOLD, 0)
	_text("Assemble your party of four", w / 2, h * 0.145, h * 0.03, C_DIM, 0)

	var card_top := h * 0.17
	var card_bottom := h * 0.85
	var card_w := (w - pad * 5) / 4
	for i in builder.drafts.size():
		var draft: Party.Draft = builder.drafts[i]
		var left := pad + i * (card_w + pad)
		var card := _r(left, card_top, left + card_w, card_bottom)
		_box(card, C_PANEL, C_BORDER)
		var hero := draft.build()
		var cx := card.get_center().x
		var line := (card_bottom - card_top) / 15.0
		var y := card_top + line * 0.3

		var sprite_size := minf(card_w * 0.7, line * 3.6)
		# A pedestal glow under a slowly turning 3D model of the hero.
		draw_set_transform(Vector2(cx, y + sprite_size * 0.93), 0.0, Vector2(1.0, 0.28))
		for k in 5:
			draw_circle(Vector2.ZERO, sprite_size * (0.42 - k * 0.07), Color(1.0, 0.72, 0.3, 0.045))
		draw_set_transform(Vector2.ZERO)
		var tt := studio.turntable(i, draft.race, draft.char_class, draft.gender) if studio != null else null
		_portrait(tt, cx - sprite_size / 2, y - line * 0.2, sprite_size, Color.WHITE, false)
		y += sprite_size + line * 0.9

		# Name (tap to edit)
		var gender_w := line * 1.3
		var name_rect := _r(left + pad, y - line * 0.8, card.end.x - pad * 1.5 - gender_w, y + line * 0.35)
		_box(name_rect, C_PANEL_LIGHT)
		_fit_text(draft.name, name_rect.get_center().x, y, line * 0.75, name_rect.size.x * 0.9, C_GOLD, 0)
		_hit(name_rect, func(): _ask_name(draft.name, func(n): draft.name = n))
		_button(_r(card.end.x - pad - gender_w, name_rect.position.y, card.end.x - pad, name_rect.end.y),
			Rules.GENDERS[draft.gender]["symbol"], func(): builder.toggle_gender(i))
		y += line * 0.75
		_text("tap name to rename", cx, y, line * 0.38, C_DIM, 0)
		y += line * 1.0

		y = _cycler(left, card, cx, card_w, line, y, pad, Rules.RACES[draft.race]["display_name"],
			func(): draft.race = _cycle(Rules.RACE_ORDER, draft.race, -1),
			func(): draft.race = _cycle(Rules.RACE_ORDER, draft.race, 1))
		y = _cycler(left, card, cx, card_w, line, y, pad, Rules.CLASSES[draft.char_class]["display_name"],
			func(): draft.char_class = _cycle(Rules.CLASS_ORDER, draft.char_class, -1),
			func(): draft.char_class = _cycle(Rules.CLASS_ORDER, draft.char_class, 1))

		# Ability scores in two columns
		for ai in 6:
			var ability: String = Rules.ABILITIES[ai]
			var ax := left + pad + (ai / 3) * (card_w - pad * 2) / 2
			var ay := y + (ai % 3) * line * 0.95
			_text(ability, ax, ay, line * 0.55, C_DIM)
			_text(str(hero.score(ability)), ax + line * 2.0, ay, line * 0.6, C_TEXT)
			_text("(%s)" % Rules.format_mod(hero.ability_mod(ability)), ax + line * 3.1, ay, line * 0.5, C_DIM)
		y += line * 3.0
		_fit_text("HP %d  AC %d  Spd %dft" % [hero.get_max_hp(), hero.get_ac(), hero.get_speed() * 5], cx, y, line * 0.55,
			card_w - pad * 2, C_TEXT, 0)
		y += line * 0.8
		_fit_text(Rules.RACES[draft.race]["trait"].get_slice(":", 0), cx, y, line * 0.45, card_w - pad * 2, C_DIM, 0)
		y += line * 0.7

		var bw := (card_w - pad * 3) / 2
		_button(_r(left + pad, y, left + pad + bw, y + line * 1.1), "Reroll", func(): builder.reroll(i))
		_button(_r(left + pad * 2 + bw, y, card.end.x - pad, y + line * 1.1), "Premade", func():
			_premade_index[i] = (_premade_index[i] + 1) % Party.HEROES.size()
			builder.apply_hero(i, Party.HEROES[_premade_index[i]]))

	var by := h * 0.875
	var bh := h * 0.09
	var bw := (w - pad * 5) / 4
	var slot := func(i: int) -> Rect2: return _r(pad + i * (bw + pad), by, pad + i * (bw + pad) + bw, by + bh)
	_button(slot.call(0), "Continue", func(): _load_slot(Session.AUTOSAVE), _slot_infos.get(Session.AUTOSAVE) != null)
	_button(slot.call(1), "Load Game", _open_menu)
	var party: Dictionary = Party.PARTIES[_premade_party_index % Party.PARTIES.size()]
	_button(slot.call(2), "Premade: %s" % party["name"], func():
		builder.apply_party(party)
		_premade_party_index = (_premade_party_index + 1) % Party.PARTIES.size())
	_button(slot.call(3), "Begin Adventure ▶", _begin_adventure, true, true)


func _begin_adventure() -> void:
	Session.start_game()
	_log_expanded = false
	_last_depth = -1
	_last_phase = -1
	screen = Screen.PLAY


static func _cycle(order: Array, current: String, step: int) -> String:
	return order[(order.find(current) + step + order.size()) % order.size()]


func _cycler(left: float, card: Rect2, cx: float, card_w: float, line: float, y: float, pad: float,
		label: String, on_prev: Callable, on_next: Callable) -> float:
	var bw := line * 1.1
	_button(_r(left + pad, y - line * 0.8, left + pad + bw, y + line * 0.3), "◀", on_prev)
	_button(_r(card.end.x - pad - bw, y - line * 0.8, card.end.x - pad, y + line * 0.3), "▶", on_next)
	_fit_text(label, cx, y, line * 0.65, card_w - bw * 2 - pad * 3, C_TEXT, 0)
	return y + line * 1.3


# =================================================================================
# Dungeon
# =================================================================================

func _draw_play(g: Game) -> void:
	_draw_map_markers(g)
	_draw_float_texts()
	_draw_map_overlay(g)
	_draw_panel(g)
	_draw_level_banner(g)
	if _sheet_hero != null:
		_draw_sheet(_sheet_hero)
	if _level_up_hero != null:
		_draw_level_up(_level_up_hero)
	if _inventory_open:
		_draw_inventory(g)
	if g.phase == Game.Phase.GAME_OVER:
		_draw_game_over(g)


func _clip_x(x: float) -> bool:
	return x > -40 and x < map_w + 40


## Health bars, leader star and status icons over the 3D world (rings and move squares are drawn in 3D).
func _draw_map_markers(g: Game) -> void:
	var d := g.dungeon
	var creatures: Array = []
	for m in g.monsters:
		if d.is_visible(m.x, m.y):
			creatures.append(m)
	creatures.append_array(g.living_heroes())
	for c in creatures:
		var pos := world.animator.display_pos(c)
		if world.is_behind(pos):
			continue
		var tall := 1.4
		if c is Monster:
			tall = 1.15 * _monster_height(c.type)
		var top := world.to_screen(pos, tall)
		if not _clip_x(top.x):
			continue
		var ppm := world.px_per_metre(pos)
		var bar_w := ppm * 0.7
		if c.hp < c.get_max_hp():
			var frac := clampf(float(c.hp) / c.get_max_hp(), 0.0, 1.0)
			var r := Rect2(top.x - bar_w / 2, top.y - 10, bar_w, maxf(5.0, ppm * 0.07))
			draw_style_box(_style(Color(0, 0, 0, 0.7), null, 0, 3, 0), r.grow(1.5))
			draw_style_box(_style(C_HP_LOW if frac < 0.35 else C_HP, null, 0, 3, 0), Rect2(r.position, Vector2(r.size.x * frac, r.size.y)))
		if c is Monster:
			if c.challenger != null:
				_text("⚔", top.x - bar_w / 2 - 6, top.y - 14, ppm * 0.22, C_GOLD, 0)
			if c.hunted_by != null:
				_text("◎", top.x + bar_w / 2 + 6, top.y - 14, ppm * 0.22, Color("8ae0ff"), 0)
		elif g.phase == Game.Phase.EXPLORING and c == g.leader():
			_text("★", top.x, top.y - 16, ppm * 0.3, C_GOLD, 0)


static func _monster_height(type: String) -> float:
	match type:
		"GIANT_RAT": return 0.45
		"KOBOLD": return 0.8
		"GOBLIN": return 0.88
		"ORC": return 1.32
		"GNOLL": return 1.32
		"BUGBEAR": return 1.45
		"OGRE": return 1.95
	return 1.15


## Damage numbers and other combat text, rising and fading above the world.
func _draw_float_texts() -> void:
	for ft in world.animator.float_texts:
		if ft["age"] < 0.0:
			continue
		var t := clampf(ft["age"] / 0.9, 0.0, 1.0)
		var alpha := clampf(1.0 - t * t, 0.0, 1.0)
		var p := world.camera.unproject_position(Vector3(ft["x"] / 16.0, 1.3 + t * 0.6, ft["y"] / 16.0 + 0.25))
		if p.x > map_w:
			continue
		var y := p.y
		# Crits pop in larger.
		var pop := 0.5 + 0.25 * (1.0 - minf(1.0, t * 4.0)) if ft["kind"] == "CRIT" else 0.38
		_text(ft["text"], p.x + 3, y + 3, tile_px * pop, Color(0, 0, 0, alpha), 0)
		_text(ft["text"], p.x, y, tile_px * pop, Color(WorldAnimator.color_of(ft["kind"]), alpha), 0)


func _draw_map_overlay(g: Game) -> void:
	var h := size.y
	var pad := h * 0.015
	var header := "Depth %d · Round %d" % [g.depth, g.round_number] if g.in_combat() else "Depth %d" % g.depth
	var hs := h * 0.04
	_box(_r(0, 0, map_w, hs * 2.6), Color(0, 0, 0, 0.63))
	_text(header, pad, hs * 1.1, hs, C_GOLD)
	var menu_w := hs * 4.0
	_fit_text(g.prompt(), pad, hs * 2.2, hs * 0.8, map_w - pad * 4 - menu_w * 2, C_TEXT)
	_button(_r(map_w - pad - menu_w, pad, map_w - pad, hs * 2.6 - pad), "Menu", _open_menu)
	var bag_label := "Bag" if g.bag.is_empty() else "Bag %d" % g.bag.size()
	_button(_r(map_w - pad * 2 - menu_w * 2, pad, map_w - pad * 2 - menu_w, hs * 2.6 - pad), bag_label, func():
		_inventory_open = true
		_inv_selected_item = null
		_inv_selected_slot = ""
		_inv_hero_index = maxi(0, g.party.find(g.living_heroes()[0]) if not g.living_heroes().is_empty() else 0),
		true, not g.bag.is_empty() and g.can_change_equipment())

	# Combat log
	var lines := 18 if _log_expanded else 5
	var ls := h * 0.032
	var log_h := ls * 1.25 * lines + pad
	var log_rect := _r(0, h - log_h, map_w, h)
	_box(log_rect, Color(0, 0, 0, 0.91) if _log_expanded else Color(0, 0, 0, 0.6))
	var visual := []
	var entries: Array = g.log_entries.slice(maxi(0, g.log_entries.size() - lines))
	for e in entries:
		for l in _wrap(e["text"], ls, map_w - pad * 2):
			visual.append([l, LOG_COLORS.get(e["kind"], C_TEXT)])
	visual = visual.slice(maxi(0, visual.size() - lines))
	for i in visual.size():
		_text(visual[i][0], pad, log_rect.position.y + pad + ls * 1.25 * (i + 1) - ls * 0.2, ls, visual[i][1])
	_hit(log_rect, func(): _log_expanded = not _log_expanded)


# =================================================================================
# Side panel
# =================================================================================

func _draw_panel(g: Game) -> void:
	var left := float(map_w)
	var w := size.x - left
	var h := size.y
	_box(_r(left, 0, size.x, h), C_PANEL)
	var pad := h * 0.012
	var row_h := h * 0.125
	var y := pad
	var active := g.active_hero()

	for i in g.party.size():
		var hero: Hero = g.party[i]
		var r := _r(left + pad, y, size.x - pad, y + row_h - pad)
		var is_active := hero == active
		_box(r, C_DARK if hero.dead else C_PANEL_LIGHT, C_GOLD if is_active else null, 4.0)
		var s := row_h - pad * 3
		if hero.dead:
			_hero_sprite(hero, r.position.x + pad, r.position.y + pad, s, Color(0.3, 0.3, 0.36))
			_text("✝", r.position.x + pad + s / 2, r.position.y + pad + s * 0.7, s * 0.5, Color(0.8, 0.8, 0.9, 0.8), 0)
		else:
			_hero_sprite(hero, r.position.x + pad, r.position.y + pad, s)
		var tx := r.position.x + pad * 2 + s
		var lead := "★ " if g.phase == Game.Phase.EXPLORING and i == g.leader_index else ""
		_fit_text(lead + hero.name, tx, r.position.y + row_h * 0.27, row_h * 0.24, r.end.x - tx - row_h * 0.5,
			C_DIM if hero.dead else C_GOLD)
		var cls_name: String = Rules.SUBCLASSES[hero.subclass]["display_name"] if hero.subclass != "" else hero.cls()["display_name"]
		_fit_text("L%d %s %s" % [hero.level, hero.race_info()["display_name"], cls_name], tx, r.position.y + row_h * 0.48,
			row_h * 0.17, r.end.x - tx - row_h * 0.5, C_DIM)

		var bar_top := r.position.y + row_h * 0.56
		var bar_right := r.end.x - pad
		var bar_h := row_h * 0.14
		var frac := maxf(0.0, float(hero.hp)) / hero.get_max_hp()
		# The bar eases toward the new value; a pale trail shows recent damage draining away.
		var st: Array = _hp_shown.get(hero, [frac, frac])
		st[0] = lerpf(st[0], frac, 0.25)
		st[1] = maxf(st[0], lerpf(st[1], st[0], 0.04)) if st[1] > st[0] else st[0]
		_hp_shown[hero] = st
		var bar := _r(tx, bar_top, bar_right, bar_top + bar_h)
		var rad := bar_h * 0.45
		draw_style_box(_style(Color(0, 0, 0, 0.8), null, 0, rad, 0), bar)
		if st[1] > st[0] + 0.002:
			draw_style_box(_style(Color(1.0, 0.85, 0.6, 0.75), null, 0, rad, 0), Rect2(bar.position, Vector2(bar.size.x * st[1], bar_h)))
		if st[0] > 0.005:
			var hp_col := C_HP_LOW if st[0] < 0.35 else C_HP
			draw_style_box(_style(hp_col, null, 0, rad, 0), Rect2(bar.position, Vector2(maxf(bar_h, bar.size.x * st[0]), bar_h)))
			draw_rect(Rect2(bar.position.x + rad, bar.position.y + 1, maxf(0.0, bar.size.x * st[0] - rad * 2), bar_h * 0.3), Color(1, 1, 1, 0.18))
		var status := "%d/%d" % [hero.hp, hero.get_max_hp()]
		if hero.dead:
			status = "DEAD"
		elif hero.is_unconscious() and hero.stable:
			status = "STABLE"
		elif hero.is_unconscious():
			status = "DYING ✓%d ✗%d" % [hero.death_successes, hero.death_failures]
		_fit_text(status, (tx + bar_right) / 2, bar_top + bar_h * 0.85, bar_h * 0.9, bar_right - tx, Color.WHITE, 0)

		var slots := ""
		if hero.is_caster():
			var total := 0
			for n in hero.spell_slots:
				total += n
			slots = "  Slots %d" % total
		_fit_text("AC %d  Pot %d%s" % [hero.get_ac(), hero.potions, slots], tx, r.end.y - pad * 0.8, row_h * 0.17,
			r.end.x - tx - row_h * 0.45, C_TEXT)

		var info := _r(r.end.x - row_h * 0.42, r.position.y + pad * 0.5, r.end.x - pad * 0.5, r.position.y + row_h * 0.38)
		_button(info, "i", func(): _sheet_hero = hero)
		var hit_right := info.position.x
		if hero.has_pending_choices() and not hero.dead:
			var up := _r(info.position.x - pad * 0.5 - info.size.x, info.position.y, info.position.x - pad * 0.5, info.end.y)
			_button(up, "▲", func(): _open_level_up(hero), true, true)
			hit_right = up.position.x
		_hit(_r(r.position.x, r.position.y, hit_right, r.end.y), func(): g.select_hero(i))
		y += row_h

	# Turn economy and initiative
	var ts := h * 0.03
	if g.in_combat():
		var line := "Enemy turn"
		if g.phase == Game.Phase.PLAYER_TURN:
			line = "Move %d  Action %s  Bonus %s" % [g.movement_left, "●" if g.action_available else "○",
				"●" if g.bonus_action_available else "○"]
		_fit_text(line, left + pad, y + ts, ts, w - pad * 2, C_TEXT)
		y += ts * 1.4
		var icon := minf(h * 0.055, (w - pad * 2) / maxi(1, g.turn_order.size()))
		for i in g.turn_order.size():
			var r := Rect2(left + pad + i * icon, y, icon, icon)
			if i == g.turn_index:
				_box(r, Color("5a4520"))
			_portrait(_creature_texture(g.turn_order[i]), r.position.x, r.position.y, r.size.x)
		y += icon + pad
	else:
		_fit_text("Gold %d   Foes slain %d" % [g.party_gold(), g.kills], left + pad, y + ts, ts, w - pad * 2, C_DIM)
		y += ts * 1.6

	# Action buttons; spells and grouped moves live in sub-menus.
	var actions := g.available_actions()
	if _sub_menu_for != active:
		_sub_menu = ""
	var spells := actions.filter(func(a): return a["id"] == "SPELL")
	var groups := []
	for a in actions:
		if a["group"] != "" and not groups.has(a["group"]):
			groups.append(a["group"])
	var buttons := []  # [label, enabled, active, Callable]
	var close := func(): _sub_menu = ""
	if _sub_menu == SPELLS and not spells.is_empty():
		buttons.append(["◀ Back", true, false, close])
		var slots := "-"
		if active != null and not active.spell_slots.is_empty():
			slots = "/".join(active.spell_slots.map(func(n): return str(n)))
		buttons.append(["Slots " + slots, false, false, func(): pass])
		for a in spells:
			buttons.append([a["label"], a["enabled"], a["active"], func():
				g.perform(a)
				_sub_menu = ""])
	elif _sub_menu != "" and groups.has(_sub_menu):
		buttons.append(["◀ Back", true, false, close])
		for a in actions:
			if a["group"] == _sub_menu:
				buttons.append([a["label"], a["enabled"], a["active"], func():
					g.perform(a)
					_sub_menu = ""])
	else:
		var first := 0
		while first < actions.size() and actions[first]["id"] in ["END_TURN", "DESCEND"]:
			var a: Dictionary = actions[first]
			buttons.append([a["label"], a["enabled"], a["active"], func(): g.perform(a)])
			first += 1
		if not spells.is_empty():
			var armed := spells.any(func(a): return a["active"])
			buttons.append(["Spells ✦" if armed else "Spells ▸", spells.any(func(a): return a["enabled"]) or armed, armed, func():
				_sub_menu = SPELLS
				_sub_menu_for = active])
		for k in range(first, actions.size()):
			var a: Dictionary = actions[k]
			if a["id"] != "SPELL" and a["group"] == "":
				buttons.append([a["label"], a["enabled"], a["active"], func(): g.perform(a)])
		for group in groups:
			var items := actions.filter(func(a): return a["group"] == group)
			var on := items.any(func(a): return a["active"])
			buttons.append([group + " ▸", items.any(func(a): return a["enabled"]) or on, on, func():
				_sub_menu = group
				_sub_menu_for = active])
	# Shrink buttons if there are more than fit.
	var cols := 2
	var rows := (buttons.size() + cols - 1) / cols
	var bh := minf(h * 0.075, (h - y - pad) / maxi(1, rows) - pad)
	var bw := (w - pad * 3) / cols
	for i in buttons.size():
		var bx := left + pad + (i % cols) * (bw + pad)
		var by := y + (i / cols) * (bh + pad)
		if by + bh > h:
			continue
		var b: Array = buttons[i]
		_button(Rect2(bx, by, bw, bh), b[0], b[3], b[1], b[2])


# =================================================================================
# Overlays
# =================================================================================

func _dim(alpha: float) -> void:
	draw_rect(Rect2(Vector2.ZERO, size), Color(0, 0, 0, alpha))


func _draw_sheet(hero: Hero) -> void:
	var w := size.x
	var h := size.y
	_dim(0.75)
	var r := _r(w * 0.12, h * 0.06, w * 0.88, h * 0.94)
	_box(r, C_PANEL, C_GOLD, 4.0)
	_hit(Rect2(Vector2.ZERO, size), func(): _sheet_hero = null)

	var pad := h * 0.03
	var s := h * 0.22
	var cls: Dictionary = hero.cls()
	_hero_sprite(hero, r.position.x + pad, r.position.y + pad, s)
	var tx := r.position.x + pad * 2 + s
	var y := r.position.y + pad + h * 0.05
	_text(hero.name, tx, y, h * 0.06, C_GOLD)
	y += h * 0.05
	var sub: String = (" · " + Rules.SUBCLASSES[hero.subclass]["display_name"]) if hero.subclass != "" else ""
	_text("Level %d %s %s%s" % [hero.level, hero.race_info()["display_name"], cls["display_name"], sub], tx, y, h * 0.035, C_TEXT)
	y += h * 0.045
	var next_xp := hero.xp_for_next_level()
	var next := (" / %d" % next_xp) if next_xp > 0 else " (max)"
	_text("XP %d%s   Proficiency +%d" % [hero.xp, next, hero.proficiency()], tx, y, h * 0.03, C_DIM)
	y += h * 0.045
	_text("HP %d/%d   AC %d   Speed %d ft   Hit dice %d" % [hero.hp, hero.get_max_hp(), hero.get_ac(), hero.get_speed() * 5, hero.hit_dice],
		tx, y, h * 0.03, C_TEXT)

	y = r.position.y + pad + s + h * 0.06
	var col_w := (r.size.x - pad * 2) / 6
	for i in 6:
		var a: String = Rules.ABILITIES[i]
		var ax := r.position.x + pad + col_w * i + col_w / 2
		_box(_r(ax - col_w * 0.45, y - h * 0.045, ax + col_w * 0.45, y + h * 0.085), C_PANEL_LIGHT)
		_text(a, ax, y - h * 0.01, h * 0.028, C_DIM, 0)
		_text(str(hero.score(a)), ax, y + h * 0.035, h * 0.045, C_TEXT, 0)
		var save := Rules.format_mod(hero.save_bonus(a)) + ("•" if a in cls["saves"] else "")
		_text("%s  sv %s" % [Rules.format_mod(hero.ability_mod(a)), save], ax, y + h * 0.07, h * 0.022, C_DIM, 0)
	y += h * 0.14

	var lh := h * 0.042
	var ls := h * 0.03
	var width := r.size.x - pad * 2
	var x := r.position.x + pad
	var melee := hero.melee_weapon()
	var bonus_text := (" (+%d)" % hero.weapon_bonus()) if hero.weapon_bonus() > 0 else ""
	_fit_text("%s%s: %s to hit, %s%s %s" % [melee["name"], bonus_text, Rules.format_mod(hero.attack_bonus(melee)), melee["damage"],
		Rules.format_mod(hero.damage_bonus(melee)), melee["damage_type"]], x, y, ls, width)
	y += lh
	var rw := hero.ranged_weapon()
	if not rw.is_empty():
		_fit_text("%s: %s to hit, %s%s, range %d ft" % [rw["name"], Rules.format_mod(hero.attack_bonus(rw)), rw["damage"],
			Rules.format_mod(hero.damage_bonus(rw)), rw["range"] * 5], x, y, ls, width)
		y += lh
	if hero.is_caster():
		_fit_text("Spell attack %s  ·  Save DC %d  ·  Slots %s" % [Rules.format_mod(hero.spell_attack_bonus()), hero.spell_save_dc(),
			"/".join(hero.spell_slots.map(func(n): return str(n)))], x, y, ls, width, C_GOLD)
		y += lh
		var known := hero.known_spells.duplicate()
		known.sort_custom(func(a, b): return Rules.SPELLS[a]["level"] < Rules.SPELLS[b]["level"])
		var names := known.map(func(sp):
			var info: Dictionary = Rules.SPELLS[sp]
			return info["display_name"] if info["level"] == 0 else "%s (%d)" % [info["display_name"], info["level"]])
		_fit_text("Spells: " + ", ".join(names), x, y, ls * 0.9, width, C_TEXT)
		y += lh * 0.9
	var gear := ", ".join(hero.equipment.values().map(func(it): return Items.name_for(it, hero)))
	_fit_text("Magic items: " + (gear if gear != "" else "none"), x, y, ls * 0.9, width, Color("5ed35e"))
	y += lh * 0.9
	_fit_text("Features: " + cls["features"], x, y, ls * 0.9, width, C_DIM)
	y += lh * 0.9
	if next_xp > 0:
		var preview := ", ".join(hero.level_gains(hero.level + 1).map(func(gn): return gn.get_slice(":", 0)))
		_fit_text("Level %d at %d XP: +%d HP, %s" % [hero.level + 1, next_xp, hero.hp_per_level(), preview], x, y, ls * 0.9, width, Color("9cd0ff"))
		y += lh * 0.9
	if hero.subclass != "":
		var feats := Rules.features_of(hero.subclass)
		var unlocked := ", ".join(feats.filter(func(f): return hero.level >= Rules.FEATURES[f]["level"]).map(func(f): return Rules.FEATURES[f]["title"]))
		var upcoming := feats.filter(func(f): return hero.level < Rules.FEATURES[f]["level"])
		var nxt := ""
		if not upcoming.is_empty():
			nxt = "  (next: %s at %d)" % [Rules.FEATURES[upcoming[0]]["title"], Rules.FEATURES[upcoming[0]]["level"]]
		_fit_text("%s: %s%s" % [Rules.SUBCLASSES[hero.subclass]["display_name"], unlocked, nxt], x, y, ls * 0.9, width, C_GOLD)
		y += lh * 0.9
	_fit_text("Race: " + hero.race_info()["trait"], x, y, ls * 0.9, width, C_DIM)
	y += lh * 0.9
	_fit_text("Armor: " + Rules.ARMORS[cls["armor"]]["name"] + ((" +%d" % hero.armor_bonus()) if hero.armor_bonus() > 0 else ""),
		x, y, ls * 0.9, width, C_DIM)
	if hero.has_pending_choices() and not hero.dead:
		var b := _r(r.end.x - pad - w * 0.18, r.position.y + pad, r.end.x - pad, r.position.y + pad + h * 0.08)
		_button(b, "▲ Level up", func():
			_sheet_hero = null
			_open_level_up(hero), true, true)
	_text("tap to close", r.end.x - pad, r.end.y - pad, h * 0.025, C_DIM, 1)


func _draw_game_over(g: Game) -> void:
	var w := size.x
	var h := size.y
	_dim(0.69)
	_hit(Rect2(Vector2.ZERO, size), func(): pass)
	_text("THE PARTY HAS FALLEN", w / 2, h * 0.36, h * 0.08, Color("e04040"), 0)
	_text("Depth %d  ·  %d foes slain  ·  %d gold" % [g.depth, g.kills, g.party_gold()], w / 2, h * 0.46, h * 0.04, C_TEXT, 0)
	_text("   ".join(g.party.map(func(p): return "%s L%d" % [p.name, p.level])), w / 2, h * 0.53, h * 0.032, C_DIM, 0)
	_button(_r(w * 0.35, h * 0.62, w * 0.65, h * 0.73), "New Party", _back_to_title, true, true)


func _back_to_title() -> void:
	Session.new_party()
	_slot_infos = {}
	_premade_index = [-1, -1, -1, -1]
	_sheet_hero = null
	_menu_open = false
	screen = Screen.PARTY


const MONTHS := ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


static func _format_time(unix_ms: int) -> String:
	var bias: int = Time.get_time_zone_from_system().get("bias", 0)
	var d := Time.get_datetime_dict_from_unix_time(unix_ms / 1000 + bias * 60)
	return "%s %d, %02d:%02d" % [MONTHS[d["month"] - 1], d["day"], d["hour"], d["minute"]]


func _draw_menu() -> void:
	var w := size.x
	var h := size.y
	var g := Session.game
	var can_save := screen == Screen.PLAY and g != null and g.phase != Game.Phase.GAME_OVER
	_dim(0.82)
	_hit(Rect2(Vector2.ZERO, size), func(): pass)
	var r := _r(w * 0.14, h * 0.06, w * 0.86, h * 0.94)
	_box(r, C_PANEL, C_GOLD, 4.0)
	var pad := h * 0.025
	_text("GAME MENU" if can_save else "LOAD GAME", r.get_center().x, r.position.y + h * 0.08, h * 0.055, C_GOLD, 0)

	var row_h := h * 0.13
	var y := r.position.y + h * 0.12
	var bw := r.size.x * 0.16
	for slot in [Session.AUTOSAVE] + Session.MANUAL_SLOTS:
		var info = _slot_infos.get(slot)
		var row := _r(r.position.x + pad, y, r.end.x - pad, y + row_h - pad * 0.6)
		_box(row, C_PANEL_LIGHT)
		var text_w := row.size.x - bw * 2 - pad * 4
		_text("Autosave" if slot == Session.AUTOSAVE else "Slot %d" % slot, row.position.x + pad, row.position.y + row_h * 0.34, row_h * 0.24, C_GOLD)
		if info == null:
			_text("Empty", row.position.x + pad, row.position.y + row_h * 0.68, row_h * 0.2, C_DIM)
		else:
			_fit_text(info["summary"], row.position.x + pad, row.position.y + row_h * 0.62, row_h * 0.19, text_w, C_TEXT)
			_text(_format_time(info["saved_at"]), row.position.x + pad, row.position.y + row_h * 0.84, row_h * 0.15, C_DIM)
		var load_r := _r(row.end.x - pad - bw, row.position.y + pad * 0.6, row.end.x - pad, row.end.y - pad * 0.6)
		_button(load_r, "Load", func(): _load_slot(slot), info != null)
		if can_save and slot != Session.AUTOSAVE:
			var save_r := _r(load_r.position.x - pad - bw, load_r.position.y, load_r.position.x - pad, load_r.end.y)
			var confirming: bool = _pending_overwrite == slot
			_button(save_r, "Overwrite?" if confirming else "Save", func():
				if info != null and not confirming:
					_pending_overwrite = slot
				else:
					var err := Session.save(slot, g)
					_toast("Saved to slot %d" % slot if err == "" else "Save failed: " + err)
					_open_menu(), true, confirming)
		y += row_h

	var by := r.end.y - pad - h * 0.09
	var half := (r.size.x - pad * 3) / 2
	var ty := by - pad - h * 0.08
	_button(_r(r.position.x + pad, ty, r.position.x + pad + half, ty + h * 0.08), "Music: " + ("On" if audio.music_on else "Off"),
		func(): audio.music_on = not audio.music_on, true, audio.music_on)
	_button(_r(r.end.x - pad - half, ty, r.end.x - pad, ty + h * 0.08), "Sound: " + ("On" if audio.sound_on else "Off"),
		func(): audio.sound_on = not audio.sound_on, true, audio.sound_on)
	_button(_r(r.position.x + pad, by, r.position.x + pad + half, by + h * 0.09), "Resume" if can_save else "Back",
		func(): _menu_open = false)
	if screen == Screen.PLAY:
		_button(_r(r.end.x - pad - half, by, r.end.x - pad, by + h * 0.09), "Save & Quit to Title", func():
			Session.autosave()
			_back_to_title())


func _draw_toast() -> void:
	var h := size.y
	var sz := h * 0.035
	var tw := _text_width(_toast_text, sz) + sz * 2
	var r := _r((size.x - tw) / 2, h * 0.42, (size.x + tw) / 2, h * 0.42 + sz * 2.2)
	_box(r, Color("201a2cf0"), C_GOLD, 3.0)
	_text(_toast_text, r.get_center().x, r.get_center().y + sz * 0.35, sz, C_TEXT, 0)


# =================================================================================
# Level up
# =================================================================================

func _open_level_up(hero: Hero) -> void:
	_level_up_hero = hero
	_asi_draft.clear()


func _draw_level_up(hero: Hero) -> void:
	var w := size.x
	var h := size.y
	_dim(0.82)
	_hit(Rect2(Vector2.ZERO, size), func(): pass)
	var r := _r(w * 0.06, h * 0.05, w * 0.94, h * 0.95)
	_box(r, C_PANEL, C_GOLD, 4.0)
	var pad := h * 0.025
	var s := h * 0.13
	_hero_sprite(hero, r.position.x + pad, r.position.y + pad, s)
	var cls_name: String = Rules.SUBCLASSES[hero.subclass]["display_name"] if hero.subclass != "" else hero.cls()["display_name"]
	_text("▲ %s · Level %d %s" % [hero.name, hero.level, cls_name], r.position.x + pad * 2 + s, r.position.y + pad + s * 0.45, h * 0.05, C_GOLD)
	var slots := ("  ·  Spell slots " + "/".join(hero.max_spell_slots.map(func(n): return str(n)))) if hero.is_caster() else ""
	_text("HP %d  ·  Proficiency +%d%s" % [hero.get_max_hp(), hero.proficiency(), slots], r.position.x + pad * 2 + s, r.position.y + pad + s * 0.85, h * 0.03, C_DIM)

	var top := r.position.y + pad * 2 + s
	var bottom := r.end.y - pad * 2 - h * 0.09
	var bw := w * 0.2
	var done_rect := _r(r.end.x - pad - bw, r.end.y - pad - h * 0.09, r.end.x - pad, r.end.y - pad)
	if hero.needs_subclass():
		_draw_subclass_choice(hero, _r(r.position.x + pad, top, r.end.x - pad, bottom))
		_button(done_rect, "Later", func(): _level_up_hero = null)
		return
	var both := hero.pending_ability_points > 0 and hero.pending_spells > 0
	var split := r.position.x + r.size.x * 0.45 if both else r.end.x
	if hero.pending_ability_points > 0:
		_draw_asi(hero, _r(r.position.x + pad, top, split - pad, bottom))
	if hero.pending_spells > 0:
		_draw_spell_picks(hero, _r(split + pad if both else r.position.x + pad, top, r.end.x - pad, bottom))
	if not hero.has_pending_choices():
		_text("All choices made!", r.get_center().x, (top + bottom) / 2, h * 0.05, C_TEXT, 0)
	_button(done_rect, "Later" if hero.has_pending_choices() else "Done", func(): _level_up_hero = null, true, not hero.has_pending_choices())


func _draw_asi(hero: Hero, area: Rect2) -> void:
	var h := size.y
	var used := 0
	for v in _asi_draft.values():
		used += v
	var budget := mini(2, hero.pending_ability_points)
	_text("Ability Score Improvement", area.position.x, area.position.y + h * 0.04, h * 0.038, C_GOLD)
	_text("+2 to one score or +1 to two (max 20). Points: %d" % (budget - used), area.position.x, area.position.y + h * 0.08, h * 0.027, C_DIM)
	var row_h := (area.size.y - h * 0.22) / 6
	for i in 6:
		var a: String = Rules.ABILITIES[i]
		var y := area.position.y + h * 0.11 + i * row_h
		var row := _r(area.position.x, y, area.end.x, y + row_h * 0.85)
		_box(row, C_PANEL_LIGHT)
		var base: int = hero.base_abilities[a]
		var add: int = _asi_draft.get(a, 0)
		var cy := row.get_center().y
		_text(Rules.ABILITY_LABELS[a], row.position.x + h * 0.02, cy + row_h * 0.12, row_h * 0.34, C_TEXT)
		_text("%d → %d" % [base, base + add] if add > 0 else str(base), row.position.x + row.size.x * 0.52, cy + row_h * 0.12, row_h * 0.36,
			C_GOLD if add > 0 else C_TEXT, 0)
		var bs := row.size.y * 0.8
		var plus := _r(row.end.x - bs - h * 0.01, cy - bs / 2, row.end.x - h * 0.01, cy + bs / 2)
		var minus := _r(plus.position.x - bs - h * 0.01, plus.position.y, plus.position.x - h * 0.01, plus.end.y)
		_button(plus, "+", func(): _asi_draft[a] = add + 1, used < budget and base + add < 20)
		_button(minus, "−", func():
			if add <= 1:
				_asi_draft.erase(a)
			else:
				_asi_draft[a] = add - 1, add > 0)
	var by := area.end.y - h * 0.09
	_button(_r(area.position.x, by, area.position.x + area.size.x * 0.48, area.end.y), "Reset", func(): _asi_draft.clear(), used > 0)
	_button(_r(area.end.x - area.size.x * 0.48, by, area.end.x, area.end.y), "Apply", func():
		if hero.improve_abilities(_asi_draft.duplicate()):
			_asi_draft.clear(), used > 0, used == budget)


func _draw_spell_picks(hero: Hero, area: Rect2) -> void:
	var h := size.y
	_text("Learn a spell (%d to choose)" % hero.pending_spells, area.position.x, area.position.y + h * 0.04, h * 0.038, C_GOLD)
	var options := hero.learnable_spells()
	options.sort_custom(func(a, b):
		var la: int = Rules.SPELLS[a]["level"]
		var lb: int = Rules.SPELLS[b]["level"]
		return la < lb if la != lb else Rules.SPELLS[a]["display_name"] < Rules.SPELLS[b]["display_name"])
	var row_h := minf(h * 0.13, (area.size.y - h * 0.07) / maxi(1, options.size()))
	for i in options.size():
		var spell: String = options[i]
		var info: Dictionary = Rules.SPELLS[spell]
		var y := area.position.y + h * 0.07 + i * row_h
		var row := _r(area.position.x, y, area.end.x, y + row_h * 0.9)
		_box(row, C_PANEL_LIGHT, C_BORDER, 2.0)
		var lvl := "Cantrip" if info["level"] == 0 else "Level %d%s" % [info["level"], " · bonus action" if info["bonus_action"] else ""]
		_fit_text(info["display_name"], row.position.x + h * 0.015, row.position.y + row_h * 0.36, row_h * 0.3, row.size.x * 0.6, C_TEXT)
		_fit_text(lvl, row.end.x - h * 0.015, row.position.y + row_h * 0.36, row_h * 0.22, row.size.x * 0.38, C_DIM, 1)
		_fit_text(info["description"], row.position.x + h * 0.015, row.position.y + row_h * 0.72, row_h * 0.2, row.size.x - h * 0.03, C_DIM)
		_hit(row, func(): hero.learn_spell(spell))


# =================================================================================
# Inventory
# =================================================================================

func _draw_inventory(g: Game) -> void:
	var w := size.x
	var h := size.y
	_dim(0.82)
	_hit(Rect2(Vector2.ZERO, size), func(): pass)
	var r := _r(w * 0.04, h * 0.04, w * 0.96, h * 0.96)
	_box(r, C_PANEL, C_GOLD, 4.0)
	var pad := h * 0.02
	_text("PARTY GEAR", r.position.x + pad, r.position.y + h * 0.06, h * 0.045, C_GOLD)
	if not g.can_change_equipment():
		_text("Gear can't be changed during combat", r.position.x + w * 0.25, r.position.y + h * 0.06, h * 0.028, C_HP_LOW)

	# Hero tabs
	var tab_w := r.size.x * 0.2
	var tab_h := (r.size.y - h * 0.1 - pad) / 4
	for i in g.party.size():
		var hero: Hero = g.party[i]
		var t := _r(r.position.x + pad, r.position.y + h * 0.09 + i * tab_h, r.position.x + pad + tab_w, r.position.y + h * 0.09 + (i + 1) * tab_h - pad)
		var sel := i == _inv_hero_index
		_box(t, C_PANEL_LIGHT if sel else C_DARK, C_GOLD if sel else null, 3.0)
		var s := t.size.y * 0.7
		_hero_sprite(hero, t.position.x + pad * 0.5, t.get_center().y - s / 2, s)
		_fit_text(hero.name, t.position.x + s + pad, t.position.y + t.size.y * 0.4, t.size.y * 0.22, t.size.x - s - pad * 1.5, C_DIM if hero.dead else C_TEXT)
		_fit_text("fallen" if hero.dead else "AC %d · HP %d" % [hero.get_ac(), hero.get_max_hp()], t.position.x + s + pad,
			t.position.y + t.size.y * 0.72, t.size.y * 0.17, t.size.x - s - pad * 1.5, C_DIM)
		_hit(t, func():
			_inv_hero_index = i
			_inv_selected_slot = "")
	if _inv_hero_index >= g.party.size():
		return
	var hero: Hero = g.party[_inv_hero_index]

	# Equipment slots
	var col_l := r.position.x + pad * 2 + tab_w
	var col_w := r.size.x * 0.36
	var slot_h := (r.size.y - h * 0.1 - pad) / Rules.ITEM_SLOTS.size()
	for i in Rules.ITEM_SLOTS.size():
		var slot: String = Rules.ITEM_SLOTS[i]
		var row := _r(col_l, r.position.y + h * 0.09 + i * slot_h, col_l + col_w, r.position.y + h * 0.09 + (i + 1) * slot_h - pad * 0.4)
		var it = hero.item(slot)
		_box(row, C_PANEL_LIGHT, C_GOLD if _inv_selected_slot == slot else null, 3.0)
		var cy := row.get_center().y + row.size.y * 0.13
		_text(Rules.ITEM_SLOT_LABELS[slot], row.position.x + pad * 0.6, cy, row.size.y * 0.32, C_DIM)
		_fit_text(Items.name_for(it, hero) if it != null else "—", row.position.x + col_w * 0.3, cy, row.size.y * 0.38, col_w * 0.68,
			Items.rarity_color(it) if it != null else C_DIM)
		_hit(row, func():
			_inv_selected_slot = slot if it != null else ""
			_inv_selected_item = null)

	# Bag and details
	var bag_l := col_l + col_w + pad
	var bag_r := r.end.x - pad
	var detail_top := r.end.y - pad - h * 0.3
	_text("Bag (%d)" % g.bag.size(), bag_l, r.position.y + h * 0.12, h * 0.032, C_GOLD)
	var item_h := h * 0.065
	var max_rows := int((detail_top - r.position.y - h * 0.14) / item_h)
	if g.bag.is_empty():
		_text("Empty. Treasure from chests and monsters lands here.", bag_l, r.position.y + h * 0.18, h * 0.025, C_DIM)
	for i in mini(max_rows, g.bag.size()):
		var it: Dictionary = g.bag[i]
		var row := _r(bag_l, r.position.y + h * 0.14 + i * item_h, bag_r, r.position.y + h * 0.14 + (i + 1) * item_h - pad * 0.4)
		_box(row, C_PANEL_LIGHT, C_GOLD if is_same(_inv_selected_item, it) else null, 3.0)
		var cy := row.get_center().y + row.size.y * 0.15
		_fit_text(Items.item_name(it), row.position.x + pad * 0.6, cy, row.size.y * 0.42, row.size.x * 0.62, Items.rarity_color(it))
		_text(Rules.ITEM_SLOT_LABELS[Items.slot(it)], row.end.x - pad * 0.6, cy, row.size.y * 0.32, C_DIM, 1)
		_hit(row, func():
			_inv_selected_item = it
			_inv_selected_slot = "")

	var detail := _r(bag_l, detail_top, bag_r, r.end.y - pad)
	_box(detail, C_DARK, C_BORDER, 2.0)
	var shown = _inv_selected_item
	if shown == null and _inv_selected_slot != "":
		shown = hero.item(_inv_selected_slot)
	if shown != null:
		var title := Items.item_name(shown) if _inv_selected_item != null else Items.name_for(shown, hero)
		_fit_text(title, detail.position.x + pad, detail.position.y + h * 0.05, h * 0.035, detail.size.x - pad * 2, Items.rarity_color(shown))
		_text("%s · %s" % [Rules.RARITIES[Items.rarity(shown)]["label"], Rules.ITEM_SLOT_LABELS[Items.slot(shown)]],
			detail.position.x + pad, detail.position.y + h * 0.09, h * 0.025, C_DIM)
		var lines := _wrap(Items.description(shown), h * 0.026, detail.size.x - pad * 2)
		for i in mini(2, lines.size()):
			_text(lines[i], detail.position.x + pad, detail.position.y + h * 0.13 + i * h * 0.033, h * 0.026, C_TEXT)
		var bw := detail.size.x * 0.55
		var br := _r(detail.end.x - pad - bw, detail.end.y - pad - h * 0.07, detail.end.x - pad, detail.end.y - pad)
		if _inv_selected_item != null:
			var usable := Items.usable_by(shown, hero) and not hero.dead
			var label := "Equip on " + hero.name
			if hero.dead:
				label = hero.name + " has fallen"
			elif not Items.usable_by(shown, hero):
				label = "Casters only"
			elif hero.item(Items.slot(shown)) != null:
				label = "Swap onto " + hero.name
			_button(br, label, func():
				if g.equip(hero, shown):
					_inv_selected_item = null, usable and g.can_change_equipment(), usable)
		else:
			_button(br, "Unequip", func():
				g.unequip(hero, _inv_selected_slot)
				_inv_selected_slot = "", g.can_change_equipment())
	else:
		_text("Tap an item in the bag or a slot to see it.", detail.position.x + pad, detail.position.y + h * 0.06, h * 0.026, C_DIM)
	_button(_r(r.end.x - pad - w * 0.12, r.position.y + pad, r.end.x - pad, r.position.y + pad + h * 0.065), "Close",
		func(): _inventory_open = false)


func _draw_subclass_choice(hero: Hero, area: Rect2) -> void:
	var h := size.y
	_text("Choose a path for " + hero.name, area.position.x, area.position.y + h * 0.04, h * 0.04, C_GOLD)
	var options := Rules.subclasses_for(hero.char_class)
	var gap := h * 0.025
	var card_w := (area.size.x - gap * (options.size() - 1)) / options.size()
	var top := area.position.y + h * 0.07
	for i in options.size():
		var sub: String = options[i]
		var info: Dictionary = Rules.SUBCLASSES[sub]
		var card := _r(area.position.x + i * (card_w + gap), top, area.position.x + i * (card_w + gap) + card_w, area.end.y)
		_box(card, C_PANEL_LIGHT, C_BORDER, 3.0)
		var p := h * 0.02
		var y := card.position.y + h * 0.055
		_text(info["display_name"], card.position.x + p, y, h * 0.045, C_GOLD)
		y += h * 0.015
		var ls := h * 0.024
		for line in _wrap(info["lore"], ls, card.size.x - p * 2):
			y += ls * 1.25
			_text(line, card.position.x + p, y, ls, C_DIM)
		y += ls * 0.8
		var limit := card.end.y - h * 0.12
		for f in Rules.features_of(sub):
			var feat: Dictionary = Rules.FEATURES[f]
			y += ls * 1.35
			if y > limit:
				break
			_text("%s (lvl %d)" % [feat["title"], feat["level"]], card.position.x + p, y, ls * 1.05, C_TEXT if hero.level >= feat["level"] else C_DIM)
			var wrapped := _wrap(feat["text"], ls * 0.92, card.size.x - p * 3)
			for k in mini(3, wrapped.size()):
				y += ls * 1.15
				if y > limit:
					break
				_text(wrapped[k], card.position.x + p * 2, y, ls * 0.92, C_DIM)
		var b := _r(card.position.x + p, card.end.y - p - h * 0.08, card.end.x - p, card.end.y - p)
		_button(b, "Become a " + info["display_name"], func(): hero.choose_subclass(sub), true, true)


# =================================================================================
# Level-up celebration
# =================================================================================

func _draw_level_banner(g: Game) -> void:
	if _level_banners.is_empty():
		return
	var notice: Dictionary = _level_banners[0]
	if notice["hero_index"] < 0 or notice["hero_index"] >= g.party.size():
		_level_banners.clear()
		return
	var hero: Hero = g.party[notice["hero_index"]]
	var duration := 2600 if _level_banners.size() > 1 else 3600
	var age := _now - _banner_start
	if _paused():
		_banner_start = _now - mini(age, 400)
	if age > duration:
		_level_banners.pop_front()
		_banner_start = _now
		return
	var h := size.y
	var fade_in := clampf(age / 250.0, 0.0, 1.0)
	var fade_out := clampf((duration - age) / 350.0, 0.0, 1.0)
	var alpha := minf(fade_in, fade_out)
	var rise := (1.0 - fade_in) * h * 0.05
	var bw := map_w * 0.62
	var lines: Array = notice["gains"].slice(0, 5)
	var bh := h * (0.26 + 0.045 * lines.size())
	var r := _r(map_w / 2.0 - bw / 2, h * 0.16 + rise, map_w / 2.0 + bw / 2, h * 0.16 + rise + bh)

	# Glowing gold frame that pulses as it appears.
	var glow := 0.5 + 0.5 * sin(age / 120.0)
	draw_rect(r, Color(Color("140f1e"), 0.88 * alpha))
	for k in 3:
		var sw := 4.0 + k * 3.0
		draw_rect(r.grow(k * 4.0 - sw / 2 + sw / 2), Color(C_GOLD, (150 - k * 45) / 255.0 * alpha * (0.6 + 0.4 * glow)), false, sw)
	_hit(r, func():
		_level_banners.pop_front()
		_banner_start = _now)

	var pad := h * 0.025
	var s := h * 0.17
	var bob := sin(age / 160.0) * h * 0.006
	if alpha > 0.05:
		_hero_sprite(hero, r.position.x + pad, r.position.y + pad + bob, s, Color(1, 1, 1, alpha))
	var tx := r.position.x + pad * 2 + s
	var pop := 1.0 + 0.25 * (1.0 - minf(1.0, age / 300.0))
	var cls_name: String = Rules.SUBCLASSES[hero.subclass]["display_name"] if hero.subclass != "" else hero.cls()["display_name"]
	_text("LEVEL %d!" % notice["level"], tx, r.position.y + pad + h * 0.075, h * 0.075 * pop, Color(C_GOLD, alpha))
	_text("%s the %s" % [hero.name, cls_name], tx, r.position.y + pad + h * 0.125, h * 0.035, Color(C_TEXT, alpha))
	_text("+%d max HP" % notice["hp_gain"], tx, r.position.y + pad + h * 0.17, h * 0.032, Color(C_HP, alpha))
	var y := r.position.y + pad + s + h * 0.05
	for line in lines:
		_fit_text("✦ " + line, r.position.x + pad, y, h * 0.03, bw - pad * 2, Color(Color("e8dca8"), alpha))
		y += h * 0.045
	if hero.has_pending_choices() and not hero.dead:
		_text("Tap ▲ on %s's portrait to choose" % hero.name, r.get_center().x, r.end.y - pad * 0.8, h * 0.026, Color(C_DIM, alpha), 0)
