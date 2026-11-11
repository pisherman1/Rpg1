class_name WorldAnimator
extends RefCounted
## Turns discrete game state into smooth motion: creatures glide between squares, lunge when
## attacking and flash when hit; spells fly as glowing projectiles with particles and light;
## the camera follows the action and shakes on big hits.
##
## [method build_frame] fills [member sprites] and [member emissive] with
## [texture, x, y, w, h, color, flash, rotate] entries and [member lights] with [x, y, radius, color, intensity].

const TILE := 16
const MOVE_SPEED := 150.0  # world pixels per second
const PROJECTILE_TIME := 0.3
const LUNGE_TIME := 0.18
const AMBIENT := Color(0.30, 0.28, 0.38)

const COLORS := {
	"FIRE": Color("ff8a20"), "RADIANT": Color("fff0a0"), "FORCE": Color("b070ff"), "ARROW": Color("d8c8a0"),
	"HEAL": Color("60f080"), "CRIT": Color("ffd040"), "DAMAGE": Color("ff5050"), "MISS": Color("b0b0c0"),
	"INFO": Color("ffd040"), "LOOT": Color("c080ff"), "LIGHTNING": Color("8ad8ff"), "NECROTIC": Color("7cff9a"),
}

## Screen size of the map view and integer pixel scale.
var view_w := 1
var view_h := 1
var scale := 4

var cam_x := 0.0
var cam_y := 0.0
var _shake := 0.0
var _shake_x := 0.0
var _shake_y := 0.0
var time := 0.0
var rng := RandomNumberGenerator.new()

var _game: Game = null
var _depth := -1

## Per-creature animation state: {"x", "y", "flash", "flash_delay", "lunge_x", "lunge_y", "lunge_t", "moving"}.
var _anims := {}
var _ghosts: Array = []       # {"tex", "x", "y", "t"}
var _projectiles: Array = []  # {"fx", "fy", "tx", "ty", "kind", "t"}
var _pulses: Array = []       # {"x", "y", "color", "t"}
var particles := Particles.new()
var _torches: Array[Vector2i] = []

var _cue_queue: Array = []  # [id, delay]
var _ready_cues: Array = []

## Floating combat text for the UI layer to draw (world-pixel positions): {"x", "y", "text", "kind", "age"}.
var float_texts: Array = []

var sprites: Array = []
var emissive: Array = []
var lights: Array = []


func _init() -> void:
	rng.seed = 42
	particles = Particles.new()


static func color_of(kind: String) -> Color:
	return COLORS.get(kind, Color.WHITE)


## Stable per-square pseudo-random number for decoration choices (same values as the Android version).
static func tile_hash(x: int, y: int) -> int:
	const M := 0xFFFFFFFF
	var h := ((x * 73856093) & M) ^ ((y * 19349663) & M) ^ 0x5BD1E995
	h &= M
	h = ((h ^ (h >> 16)) * 0x85EBCA6B) & M
	h = ((h ^ (h >> 13)) * 0xC2B2AE35) & M
	return (h ^ (h >> 16)) & 0x7FFFFFFF


func drain_cues() -> Array:
	var out := _ready_cues
	_ready_cues = []
	return out


func _cue(id: String, delay: float = 0.0) -> void:
	if delay <= 0.0:
		_ready_cues.append(id)
	else:
		_cue_queue.append([id, delay])


func set_viewport(width: int, height: int, pixel_scale: int) -> void:
	view_w = maxi(1, width)
	view_h = maxi(1, height)
	scale = maxi(1, pixel_scale)


func cam_left() -> float:
	return cam_x - view_w / (2.0 * scale) + _shake_x


func cam_top() -> float:
	return cam_y - view_h / (2.0 * scale) + _shake_y


func to_screen(wx: float, wy: float) -> Vector2:
	return Vector2((wx - cam_left()) * scale, (wy - cam_top()) * scale)


func screen_to_tile(s: Vector2) -> Vector2i:
	return Vector2i(floori((cam_left() + s.x / scale) / TILE), floori((cam_top() + s.y / scale) / TILE))


## Smoothed top-left world position of a creature, or its square if not tracked.
func display_pos(c: Creature) -> Vector2:
	var a = _anims.get(c)
	return Vector2(a["x"], a["y"]) if a != null else Vector2(c.x * TILE, c.y * TILE)


# =================================================================================
# Update
# =================================================================================

func update(g: Game, fx: Array, dt_seconds: float) -> void:
	var dt := clampf(dt_seconds, 0.0, 0.05)
	time += dt
	if g != _game or g.depth != _depth:
		_reset(g)

	var present := {}
	for c in g.living_heroes() + g.monsters:
		present[c] = true
		if not _anims.has(c):
			_anims[c] = {"x": float(c.x * TILE), "y": float(c.y * TILE), "flash": 0.0, "flash_delay": 0.0,
				"lunge_x": 0.0, "lunge_y": 0.0, "lunge_t": 0.0, "moving": false}
		var a: Dictionary = _anims[c]
		var tx := float(c.x * TILE)
		var ty := float(c.y * TILE)
		var dx: float = tx - a["x"]
		var dy: float = ty - a["y"]
		var dist := sqrt(dx * dx + dy * dy)
		a["moving"] = dist > 0.5
		if dist > TILE * 3:
			a["x"] = tx
			a["y"] = ty
		elif dist > 0.0:
			var step := MOVE_SPEED * dt
			if step >= dist:
				a["x"] = tx
				a["y"] = ty
			else:
				a["x"] += dx / dist * step
				a["y"] += dy / dist * step
		if a["flash_delay"] > 0.0:
			a["flash_delay"] -= dt
			if a["flash_delay"] <= 0.0:
				a["flash"] = 1.0
		else:
			a["flash"] = maxf(0.0, a["flash"] - dt * 6.0)
		a["lunge_t"] = maxf(0.0, a["lunge_t"] - dt)
	# Whoever disappeared died: fade them out in a puff of dust.
	for c in _anims.keys():
		if present.has(c):
			continue
		var a: Dictionary = _anims[c]
		_anims.erase(c)
		if c is Monster and g.dungeon.is_visible(c.x, c.y):
			_ghosts.append({"tex": Atlas.texture(Atlas.monster_key(c.type)), "x": a["x"], "y": a["y"], "t": 0.0})
			particles.dust(a["x"] + 8, a["y"] + 10, 14)
			_cue("MONSTER_DIE", 0.05)
		elif c is Hero:
			particles.dust(a["x"] + 8, a["y"] + 10, 18)

	_handle_fx(g, fx)
	var still: Array = []
	for c in _cue_queue:
		c[1] -= dt
		if c[1] <= 0.0:
			_ready_cues.append(c[0])
		else:
			still.append(c)
	_cue_queue = still

	var flying: Array = []
	for p in _projectiles:
		p["t"] += dt
		if p["t"] < 0.0:
			flying.append(p)
			continue
		var k := minf(p["t"] / PROJECTILE_TIME, 1.0)
		var x: float = p["fx"] + (p["tx"] - p["fx"]) * k
		var y: float = p["fy"] + (p["ty"] - p["fy"]) * k
		match p["kind"]:
			"FIRE":
				particles.ember(x, y)
				particles.ember(x, y)
			"RADIANT":
				if rng.randf() < 0.6:
					particles.motes(x, y, Color("fff0a0"), 1, 2.0)
			"FORCE":
				if rng.randf() < 0.7:
					particles.burst(x, y, Color("b070ff"), 1, 12.0, 0.0, 0.3)
			"LIGHTNING":
				particles.burst(x, y, Color("b8ecff"), 2, 30.0, 0.0, 0.15)
			"NECROTIC":
				if rng.randf() < 0.8:
					particles.motes(x, y, Color("7cff9a"), 1, 3.0)
		if p["t"] >= PROJECTILE_TIME:
			_impact(p["tx"], p["ty"], p["kind"])
		else:
			flying.append(p)
	_projectiles = flying
	for gh in _ghosts:
		gh["t"] += dt
	_ghosts = _ghosts.filter(func(gh): return gh["t"] <= 0.6)
	for p in _pulses:
		p["t"] += dt
	_pulses = _pulses.filter(func(p): return p["t"] <= 0.4)
	for t in float_texts:
		t["age"] += dt
	float_texts = float_texts.filter(func(t): return t["age"] <= 1.0)

	# Torches shed the occasional ember.
	for t in _torches:
		if g.dungeon.is_visible(t.x, t.y) and rng.randf() < dt * 3.0:
			particles.ember(t.x * TILE + 8.0, t.y * TILE + 4.0)
	particles.update(dt)

	_update_camera(g, dt)
	_shake *= exp(-dt * 9.0)
	_shake_x = (rng.randf() * 2 - 1) * _shake if _shake > 0.2 else 0.0
	_shake_y = (rng.randf() * 2 - 1) * _shake if _shake > 0.2 else 0.0


func _reset(g: Game) -> void:
	_game = g
	_depth = g.depth
	_anims.clear()
	_ghosts.clear()
	_projectiles.clear()
	_pulses.clear()
	particles.clear()
	float_texts.clear()
	_cue_queue.clear()
	var d := g.dungeon
	_torches.clear()
	for y in d.height:
		for x in d.width:
			if d.at(x, y) == Dungeon.Tile.WALL and d.is_walkable(x, y + 1) and tile_hash(x, y) % 6 == 0:
				var near := false
				for t in _torches:
					if absi(t.x - x) + absi(t.y - y) < 4:
						near = true
						break
				if not near:
					_torches.append(Vector2i(x, y))
	var f := _focus(g)
	cam_x = f.x
	cam_y = f.y


func _creature_at(g: Game, x: int, y: int) -> Creature:
	var h := g.hero_at(x, y)
	return h if h != null else g.monster_at(x, y)


func _handle_fx(g: Game, fx: Array) -> void:
	var bolt_targets := {}
	for f in fx:
		if f["type"] == "bolt":
			bolt_targets[Vector2i(f["to_x"], f["to_y"])] = true
	var bolt_index := 0
	for f in fx:
		if f["type"] == "bolt":
			_projectiles.append({"fx": f["from_x"] * TILE + 8.0, "fy": f["from_y"] * TILE + 8.0,
				"tx": f["to_x"] * TILE + 8.0, "ty": f["to_y"] * TILE + 8.0, "kind": f["kind"], "t": -bolt_index * 0.06})
			if bolt_index < 3:
				var id := "MAGIC"
				match f["kind"]:
					"ARROW": id = "BOW"
					"FIRE": id = "FIRE_CAST"
					"RADIANT": id = "HOLY"
					"LIGHTNING": id = "ZAP"
					"NECROTIC": id = "NECRO"
				_cue(id, bolt_index * 0.06)
			bolt_index += 1
			continue
		var fxp := Vector2i(f["x"], f["y"])
		var kind: String = f["kind"]
		var text: String = f["text"]
		var ranged := bolt_targets.has(fxp)
		var delay := PROJECTILE_TIME if ranged else 0.08
		float_texts.append({"x": fxp.x * TILE + 8.0, "y": fxp.y * TILE + 4.0, "text": text, "kind": kind, "age": -delay - 0.05})
		var target := _creature_at(g, fxp.x, fxp.y)
		var target_anim = _anims.get(target) if target != null else null
		var cue := _text_cue(kind, text)
		if cue != "":
			_cue(cue, delay)
		var cx := fxp.x * TILE + 8.0
		var cy := fxp.y * TILE + 8.0
		match kind:
			"DAMAGE", "CRIT":
				if target_anim != null:
					target_anim["flash_delay"] = delay
				_shake = maxf(_shake, 5.0 if kind == "CRIT" else (2.5 if target is Hero else 1.2))
				if not ranged:
					particles.burst(cx, cy, Color("ffd040") if kind == "CRIT" else Color("ff5050"), 18 if kind == "CRIT" else 8)
			"HEAL":
				particles.motes(cx, cy + 2, Color("60f080"), 0 if text == "LEVEL UP" else 14)
				_pulses.append({"x": cx, "y": cy, "color": Color("60f080"), "t": 0.0})
				if text == "LEVEL UP":
					particles.motes(cx, cy + 2, Color("ffd040"), 24, 8.0)
					_pulses.append({"x": cx, "y": cy, "color": Color("ffd040"), "t": 0.0})
			"INFO":
				particles.burst(cx, cy, Color("ffd040"), 8, 25.0, 40.0)
			"LOOT":
				particles.motes(cx, cy + 2, Color("c080ff"), 20, 7.0)
				_pulses.append({"x": cx, "y": cy, "color": Color("c080ff"), "t": 0.0})
		# Melee attacker lunges at the target.
		if not ranged and kind in ["DAMAGE", "CRIT", "MISS"]:
			var attacker := g.current()
			if attacker != null and attacker != target and maxi(absi(attacker.x - fxp.x), absi(attacker.y - fxp.y)) == 1:
				var a = _anims.get(attacker)
				if a != null:
					a["lunge_x"] = float(fxp.x - attacker.x)
					a["lunge_y"] = float(fxp.y - attacker.y)
					a["lunge_t"] = LUNGE_TIME


## The sound for a floating combat text, or "".
func _text_cue(kind: String, text: String) -> String:
	match kind:
		"DAMAGE": return "HIT"
		"CRIT": return "CRIT"
		"MISS": return "MISS" if text == "miss" else ""
		"HEAL":
			if text == "LEVEL UP":
				return "LEVEL_UP"
			if text in ["revived", "rested"] or text.ends_with("HD"):
				return ""
			return "HEAL"
		"LOOT": return "LOOT"
		"NECROTIC": return "RAISE" if text == "rise!" else ""
		"RADIANT": return "HOLY" if text == "blessed" else ""
		"INFO":
			if text.begins_with("+") and text.ends_with("g"):
				return "COIN"
			match text:
				"Zzz": return "SLEEP"
				"challenged": return "CHALLENGE"
				"marked": return "MARK"
	return ""


func _impact(x: float, y: float, kind: String) -> void:
	if kind == "FIRE":
		_cue("EXPLOSION")
	elif kind == "LIGHTNING":
		_cue("THUNDER")
	var color := color_of(kind)
	match kind:
		"ARROW":
			particles.burst(x, y, Color("d8c8a0"), 5, 30.0)
		"FIRE":
			particles.burst(x, y, Color("ff8a20"), 22, 70.0, 30.0, 0.5)
			for i in 6:
				particles.ember(x, y)
		_:
			particles.burst(x, y, color, 14, 55.0, 20.0)
	if kind != "ARROW":
		_pulses.append({"x": x, "y": y, "color": color, "t": 0.0})


func _focus(g: Game) -> Vector2:
	var cur := g.current()
	var target: Creature
	if g.phase == Game.Phase.MONSTER_TURN and cur is Monster and g.dungeon.is_visible(cur.x, cur.y):
		target = cur
	else:
		target = g.active_hero()
		if target == null:
			var living := g.living_heroes()
			target = living[0] if not living.is_empty() else g.party[0]
	return display_pos(target) + Vector2(8, 8)


func _update_camera(g: Game, dt: float) -> void:
	var f := _focus(g)
	var k := 1.0 - exp(-dt * 6.0)
	cam_x += (f.x - cam_x) * k
	cam_y += (f.y - cam_y) * k


# =================================================================================
# Frame building
# =================================================================================

func _sprite(out: Array, tex: Texture2D, x: float, y: float, w: float = 16.0, h: float = 16.0,
		color: Color = Color.WHITE, flash: float = 0.0, rotate: bool = false) -> void:
	out.append([tex, x, y, w, h, color, flash, rotate])


func _light(x: float, y: float, radius: float, color: Color, intensity: float) -> void:
	lights.append([x, y, radius, color, intensity])


func _flicker(seed: int) -> float:
	return 1.0 + 0.05 * sin(time * 11.0 + seed) + 0.035 * sin(time * 23.7 + seed * 2.3) + 0.02 * sin(time * 5.1 + seed)


func build_frame(g: Game) -> void:
	sprites.clear()
	emissive.clear()
	lights.clear()
	var d := g.dungeon
	var left := floori(cam_left() / TILE) - 1
	var top := floori(cam_top() / TILE) - 1
	var right := left + view_w / (scale * TILE) + 3
	var bottom := top + view_h / (scale * TILE) + 3

	# Terrain
	var wall_top := Atlas.texture("wall_top")
	var wall_front := Atlas.texture("wall")
	var wall_shadow := Atlas.texture("wall_shadow")
	var floors := [Atlas.texture("floor"), Atlas.texture("floor_cracked"), Atlas.texture("floor_moss"), Atlas.texture("floor_rubble")]
	var stairs := Atlas.texture("stairs")
	var torch_frames := [Atlas.texture("torch_a"), Atlas.texture("torch_b")]
	for y in range(top, bottom + 1):
		for x in range(left, right + 1):
			if not d.is_explored(x, y):
				continue
			var px := float(x * TILE)
			var py := float(y * TILE)
			match d.at(x, y):
				Dungeon.Tile.WALL:
					_sprite(sprites, wall_front if d.is_walkable(x, y + 1) else wall_top, px, py)
				Dungeon.Tile.FLOOR:
					var h := tile_hash(x, y) % 100
					_sprite(sprites, floors[0 if h < 82 else 1 + h % 3], px, py)
				Dungeon.Tile.STAIRS:
					_sprite(sprites, stairs, px, py)
					_light(px + 8, py + 8, 36.0, Color("6a9cff"), 0.7 * _flicker(x * 7))
			if d.is_walkable(x, y) and d.at(x, y - 1) == Dungeon.Tile.WALL:
				_sprite(sprites, wall_shadow, px, py)
	for i in _torches.size():
		var t := _torches[i]
		if not d.is_explored(t.x, t.y):
			continue
		var frame := int(time * 7.0 + i * 1.7) % 2
		_sprite(sprites, torch_frames[frame], t.x * TILE, t.y * TILE)
		_light(t.x * TILE + 8.0, t.y * TILE + 12.0, 70.0 * _flicker(i * 13), Color("ff8a3c"), 0.9)

	# Things on the floor
	var bones := Atlas.texture("bones")
	var grave := Atlas.texture("grave")
	for p in g.corpses:
		if d.is_explored(p.x, p.y):
			_sprite(sprites, bones, p.x * TILE, p.y * TILE)
	for p in g.graves:
		_sprite(sprites, grave, p.x * TILE, p.y * TILE)
	for it in d.items:
		var ix: int = it["x"]
		var iy: int = it["y"]
		if not d.is_explored(ix, iy):
			continue
		var px := float(ix * TILE)
		var py := float(iy * TILE)
		var kind: String = it["kind"]
		var bob := floorf(sin(time * 3.0 + ix) * 1.2) if kind in ["POTION", "TREASURE"] else 0.0
		_sprite(sprites, Atlas.texture(kind.to_lower()), px, py + bob)
		if kind == "POTION":
			_light(px + 8, py + 9, 14.0, Color("ff4040"), 0.5)
		if kind == "TREASURE" and d.is_visible(ix, iy):
			var rarity: Color = Items.rarity_color(it["item"]) if it["item"] != null else Color.WHITE
			_light(px + 9, py + 8, 22.0, rarity, 0.9 * _flicker(ix * 5 + iy))
			if rng.randf() < 0.04:
				particles.motes(px + 10, py + 7, rarity, 1, 3.0)

	# Creatures, back to front
	var draws: Array = []
	for h in g.living_heroes():
		draws.append([h, display_pos(h)])
	for m in g.monsters:
		if d.is_visible(m.x, m.y):
			draws.append([m, display_pos(m)])
	draws.sort_custom(func(a, b): return a[1].y < b[1].y)
	var shadow := Atlas.texture("shadow")
	for dr in draws:
		_sprite(sprites, shadow, dr[1].x, dr[1].y + 1.0, 16, 16, Color(1, 1, 1, 0.9))
	for ghost in _ghosts:
		var ga: float = 1.0 - ghost["t"] / 0.6
		_sprite(sprites, ghost["tex"], ghost["x"], ghost["y"] - ghost["t"] * 10.0, 16, 16, Color(1, 1, 1, ga), 0.5 * ga)
	for dr in draws:
		var c: Creature = dr[0]
		var pos: Vector2 = dr[1]
		var a = _anims.get(c)
		var lunge := 0.0
		if a != null and a["lunge_t"] > 0.0:
			lunge = sin((1.0 - a["lunge_t"] / LUNGE_TIME) * PI) * 4.0
		var lx: float = (a["lunge_x"] if a != null else 0.0) * lunge
		var ly: float = (a["lunge_y"] if a != null else 0.0) * lunge
		var flash: float = a["flash"] if a != null else 0.0
		var moving: bool = a != null and a["moving"]
		if c is Hero:
			var idx := g.party.find(c)
			var down: bool = c.is_unconscious()
			var bob := 0.0
			if not moving and not down and sin(time * 3.4 + idx * 1.6) > 0.3:
				bob = -1.0
			var w := floorf(16.0 * Atlas.hero_scale(c.race))
			_sprite(sprites, Atlas.hero_texture(c), pos.x + lx + (16.0 - w) / 2, pos.y + ly + 16.0 - w + bob, w, w,
				Color(0.75, 0.7, 0.7) if down else Color.WHITE, flash, down)
			if not down:
				_light(pos.x + 8, pos.y + 8, 92.0 * _flicker(idx * 31), Color("ffb070"), 1.0)
		elif c is Monster:
			var bob := 0.0
			if not moving and sin(time * 2.6 + c.x * 1.3 + c.y) > 0.5:
				bob = -1.0
			# The party's undead minions glow a ghostly green.
			var tint := Color(0.7, 1.0, 0.8) if c.ally else Color.WHITE
			_sprite(sprites, Atlas.texture(Atlas.monster_key(c.type)), pos.x + lx, pos.y + ly + bob, 16, 16, tint, flash)
			if c.ally:
				_light(pos.x + 8, pos.y + 8, 26.0, Color("60ff90"), 0.6)
			if c.marked:
				_light(pos.x + 8, pos.y + 8, 24.0, Color("ffe070"), 0.8)
				if rng.randf() < 0.15:
					particles.motes(pos.x + 8, pos.y + 8, Color("fff0a0"), 1, 5.0)

	# Glowing things drawn after lighting
	var pixel := Atlas.texture("pixel")
	var orb := Atlas.texture("orb")
	for p in _projectiles:
		if p["t"] < 0.0:
			continue
		var k := minf(p["t"] / PROJECTILE_TIME, 1.0)
		var x: float = p["fx"] + (p["tx"] - p["fx"]) * k
		var y: float = p["fy"] + (p["ty"] - p["fy"]) * k
		var col := color_of(p["kind"])
		if p["kind"] == "ARROW":
			_sprite(emissive, pixel, x - 1.5, y - 0.5, 3, 1, Color(col, 0.9))
		else:
			_sprite(emissive, orb, x - 6, y - 6, 12, 12, col)
			_light(x, y, 44.0, col, 1.3)
	for p in particles.list:
		var pa := clampf(p[4] / p[5], 0.0, 1.0)
		var s: float = p[7]
		_sprite(emissive, pixel, p[0] - s / 2, p[1] - s / 2, s, s, Color(p[6], pa))
	for p in _pulses:
		var k: float = 1.0 - p["t"] / 0.4
		_light(p["x"], p["y"], 40.0 + 30.0 * (1.0 - k), p["color"], 1.6 * k)
