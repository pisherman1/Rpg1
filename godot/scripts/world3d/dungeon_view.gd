class_name DungeonView
extends Node3D
## The 3D dungeon: level geometry with fog of war, torches and lanterns, animated heroes and monsters,
## props, spell effects and ground markers. Motion and timing come from [WorldAnimator] (world pixels,
## 16 per square); one square is one metre here.

const WALL_H := 1.25
const LOW_WALL_H := 0.32
const PX := 16.0
const CAM_DIST := 7.0
const CAM_PITCH := 58.0
const FOG_MEMORY := Color(0.24, 0.27, 0.4)
const MAX_SHADOW_TORCHES := 4

var animator := WorldAnimator.new()
var game: Game = null
## Fraction of the screen width covered by the map (the rest is the side panel).
var map_fraction := 0.66

var camera: Camera3D
var _env: WorldEnvironment
var _level: Node3D
var _floors: MultiMeshInstance3D
var _walls: MultiMeshInstance3D
var _low_walls: MultiMeshInstance3D
var _caps: MultiMeshInstance3D
var _floor_tiles: Array[Vector2i] = []
var _wall_tiles: Array[Vector2i] = []
var _low_wall_tiles: Array[Vector2i] = []
var _torches: Array = []  # [tile, light, flame node]
var _decor_nodes: Array = []  # [tile, node]
var _stairs_light: OmniLight3D
var _built_for: Dungeon = null
var _last_vis := PackedByteArray()
var _last_explored := PackedByteArray()

var _actors := {}   # Creature -> {"fig", "light", "ring", "facing"}
var _dying: Array = []  # {"fig", "t", "light"}
var _props := {}    # key -> Node3D
var _proj_pool: Array = []
var _pulse_pool: Array[OmniLight3D] = []
var _sparks: MultiMeshInstance3D
var _dust: GPUParticles3D
var _reach: MultiMeshInstance3D
var _time := 0.0

var _ring_mats := {}


func _ready() -> void:
	_env = WorldEnvironment.new()
	var e := Environment.new()
	e.background_mode = Environment.BG_COLOR
	e.background_color = Color(0.01, 0.008, 0.015)
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.42, 0.44, 0.62)
	e.ambient_light_energy = 0.48
	e.tonemap_mode = Environment.TONE_MAPPER_ACES
	e.tonemap_exposure = 1.1
	e.glow_enabled = true
	e.glow_intensity = 0.9
	e.glow_strength = 1.0
	e.glow_bloom = 0.08
	e.glow_hdr_threshold = 0.9
	e.glow_blend_mode = Environment.GLOW_BLEND_MODE_SCREEN
	e.fog_enabled = true
	e.fog_light_color = Color(0.05, 0.05, 0.09)
	e.fog_density = 0.012
	e.adjustment_enabled = true
	e.adjustment_saturation = 1.1
	e.adjustment_contrast = 1.05
	_env.environment = e
	add_child(_env)

	camera = Camera3D.new()
	camera.fov = 38.0
	camera.near = 0.3
	camera.far = 60.0
	add_child(camera)

	_level = Node3D.new()
	add_child(_level)

	var spark_mm := MultiMesh.new()
	spark_mm.transform_format = MultiMesh.TRANSFORM_3D
	spark_mm.use_colors = true
	var quad := QuadMesh.new()
	quad.size = Vector2(1, 1)
	spark_mm.mesh = quad
	_sparks = MultiMeshInstance3D.new()
	_sparks.multimesh = spark_mm
	_sparks.material_override = Mats.spark()
	_sparks.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(_sparks)

	var reach_mm := MultiMesh.new()
	reach_mm.transform_format = MultiMesh.TRANSFORM_3D
	var plane := PlaneMesh.new()
	plane.size = Vector2(0.9, 0.9)
	reach_mm.mesh = plane
	_reach = MultiMeshInstance3D.new()
	_reach.multimesh = reach_mm
	var reach_mat: StandardMaterial3D = Mats.marker(Color(0.55, 0.8, 1.0, 0.32)).duplicate()
	reach_mat.albedo_texture = _outline_texture()
	_reach.material_override = reach_mat
	_reach.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(_reach)

	# Dust motes drifting through the torchlight around the camera's focus.
	_dust = GPUParticles3D.new()
	_dust.amount = 90
	_dust.lifetime = 9.0
	_dust.preprocess = 9.0
	_dust.visibility_aabb = AABB(Vector3(-8, -1, -8), Vector3(16, 4, 16))
	var pm := ParticleProcessMaterial.new()
	pm.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
	pm.emission_box_extents = Vector3(6, 0.8, 5)
	pm.gravity = Vector3(0, 0.01, 0)
	pm.initial_velocity_min = 0.02
	pm.initial_velocity_max = 0.08
	pm.direction = Vector3(1, 0.2, 0.3)
	pm.spread = 180.0
	pm.turbulence_enabled = true
	pm.turbulence_noise_strength = 0.4
	pm.scale_min = 0.6
	pm.scale_max = 1.4
	var fade := Gradient.new()
	fade.set_color(0, Color(1, 0.85, 0.6, 0))
	fade.add_point(0.3, Color(1, 0.85, 0.6, 0.35))
	fade.add_point(0.7, Color(1, 0.85, 0.6, 0.35))
	fade.set_color(fade.get_point_count() - 1, Color(1, 0.85, 0.6, 0))
	var ramp := GradientTexture1D.new()
	ramp.gradient = fade
	pm.color_ramp = ramp
	_dust.process_material = pm
	var mote := QuadMesh.new()
	mote.size = Vector2(0.025, 0.025)
	mote.material = Mats.spark()
	_dust.draw_pass_1 = mote
	add_child(_dust)

	for c in [["gold", Color(1.0, 0.8, 0.25)], ["red", Color(1.0, 0.25, 0.2)], ["orange", Color(1.0, 0.5, 0.2)],
			["green", Color(0.35, 1.0, 0.5)]]:
		_ring_mats[c[0]] = Mats.marker(Color(c[1], 0.85), true)


# =================================================================================
# Coordinates
# =================================================================================

## World-pixel position (top-left of a square) to the centre of that square on the floor.
static func px_to_world(px: Vector2, height: float = 0.0) -> Vector3:
	return Vector3(px.x / PX + 0.5, height, px.y / PX + 0.5)


## Screen position of a world-pixel point raised [param height] metres.
func to_screen(px: Vector2, height: float = 0.0) -> Vector2:
	return camera.unproject_position(px_to_world(px, height))


## Screen pixels per metre near a world-pixel point (for sizing overlays).
func px_per_metre(px: Vector2) -> float:
	var p := px_to_world(px, 0.5)
	return camera.unproject_position(p + Vector3(0.5, 0, 0)).distance_to(camera.unproject_position(p - Vector3(0.5, 0, 0)))


func is_behind(px: Vector2) -> bool:
	return camera.is_position_behind(px_to_world(px))


## The map square under a screen position (ray onto the floor).
func tile_at(screen: Vector2) -> Vector2i:
	var from := camera.project_ray_origin(screen)
	var dir := camera.project_ray_normal(screen)
	if absf(dir.y) < 0.0001:
		return Vector2i(-1, -1)
	var t := -from.y / dir.y
	var hit := from + dir * t
	return Vector2i(floori(hit.x), floori(hit.z))


# =================================================================================
# Frame
# =================================================================================

func step(fx: Array, dt: float) -> void:
	if game == null:
		visible = false
		return
	visible = true
	_time += dt
	var vp := get_viewport().get_visible_rect().size
	animator.set_viewport(int(vp.x * map_fraction), int(vp.y), 64)
	animator.update(game, fx, dt)
	if _built_for != game.dungeon:
		_build_level(game.dungeon)
	_update_fog()
	_update_camera(vp)
	_update_torches()
	_sync_actors(dt)
	_sync_props(dt)
	_update_projectiles()
	_update_particles()
	_update_pulses()
	_update_markers()


func _update_camera(vp: Vector2) -> void:
	var focus := Vector3(animator.cam_x / PX, 0.0, animator.cam_y / PX)
	var shake := Vector3(animator._shake_x, 0, animator._shake_y) / PX
	var pitch := deg_to_rad(CAM_PITCH)
	var back := Vector3(0, sin(pitch), cos(pitch)) * CAM_DIST
	camera.position = focus + back + shake
	if _dust != null:
		_dust.position = focus + Vector3(0, 0.9, 0)
	camera.look_at(focus + shake, Vector3.UP)
	# Centre the focus in the map area rather than the whole screen.
	var aspect := vp.x / maxf(1.0, vp.y)
	var half_w := tan(deg_to_rad(camera.fov / 2)) * aspect * CAM_DIST
	camera.h_offset = half_w * (1.0 - map_fraction)


# =================================================================================
# Level geometry
# =================================================================================

func _build_level(d: Dungeon) -> void:
	for c in _level.get_children():
		c.queue_free()
	for a in _actors.values():
		a["fig"].queue_free()
	_actors.clear()
	for p in _props.values():
		p.queue_free()
	_props.clear()
	for dying in _dying:
		dying["fig"].queue_free()
	_dying.clear()
	_built_for = d
	_last_vis = PackedByteArray()
	_last_explored = PackedByteArray()
	_floor_tiles.clear()
	_wall_tiles.clear()
	_low_wall_tiles.clear()
	for y in d.height:
		for x in d.width:
			if d.is_walkable(x, y):
				if d.at(x, y) == Dungeon.Tile.FLOOR:
					_floor_tiles.append(Vector2i(x, y))
				continue
			var touches_floor := false
			for dir in Dungeon.DIRS:
				if d.is_walkable(x + dir.x, y + dir.y):
					touches_floor = true
					break
			if not touches_floor:
				continue
			# Walls with open floor behind them (to the north) would hide that floor from the camera.
			if d.is_walkable(x, y - 1) or d.is_walkable(x - 1, y - 1) and d.is_walkable(x + 1, y - 1):
				_low_wall_tiles.append(Vector2i(x, y))
			else:
				_wall_tiles.append(Vector2i(x, y))
	_floors = _tile_multimesh(_floor_tiles, Figure.box(Vector3(1, 0.2, 1)), Mats.textured("floor", 0.5, 0.85, true), -0.1)
	_walls = _tile_multimesh(_wall_tiles, Figure.box(Vector3(1, WALL_H, 1)), Mats.textured("wall", 0.5, 0.9, true), WALL_H / 2)
	_low_walls = _tile_multimesh(_low_wall_tiles, Figure.box(Vector3(1, LOW_WALL_H, 1)), Mats.textured("wall", 0.5, 0.9, true), LOW_WALL_H / 2)
	# Dark stone caps so the tops of walls read as solid rock, not floor.
	_caps = _tile_multimesh(_wall_tiles, Figure.box(Vector3(1.02, 0.08, 1.02)), Mats.textured("rock", 0.6, 1.0, true), WALL_H + 0.04)
	_build_stairs(d)
	_build_torches()
	_build_decor(d)


func _tile_multimesh(tiles: Array[Vector2i], mesh: Mesh, mat: Material, y: float) -> MultiMeshInstance3D:
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = true
	mm.mesh = mesh
	mm.instance_count = tiles.size()
	for i in tiles.size():
		mm.set_instance_transform(i, Transform3D(Basis.from_scale(Vector3.ZERO), Vector3(tiles[i].x + 0.5, y, tiles[i].y + 0.5)))
		mm.set_instance_color(i, Color.BLACK)
	var mi := MultiMeshInstance3D.new()
	mi.multimesh = mm
	mi.material_override = mat
	_level.add_child(mi)
	return mi


func _build_stairs(d: Dungeon) -> void:
	var stairs := Node3D.new()
	stairs.position = Vector3(d.stairs.x + 0.5, 0, d.stairs.y + 0.5)
	stairs.name = "Stairs"
	_level.add_child(stairs)
	var rock := Mats.textured("rock", 0.8, 0.9)
	# A dark shaft with steps spiralling down out of sight.
	Models._mesh(stairs, Figure.box(Vector3(1, 0.2, 1)), Mats.solid(Color(0.02, 0.02, 0.03), 1.0), Vector3(0, -1.0, 0))
	for i in 4:
		Models._mesh(stairs, Figure.box(Vector3(0.8, 0.12, 0.24)), rock, Vector3(0, -0.08 - i * 0.2, -0.36 + i * 0.24))
	for side in [-1, 1]:
		Models._mesh(stairs, Figure.box(Vector3(0.1, 0.3, 1.0)), rock, Vector3(side * 0.46, 0.05, 0))
	_stairs_light = OmniLight3D.new()
	_stairs_light.light_color = Color(0.45, 0.62, 1.0)
	_stairs_light.omni_range = 3.0
	_stairs_light.light_energy = 1.4
	_stairs_light.position = Vector3(0, 0.3, 0)
	stairs.add_child(_stairs_light)
	stairs.visible = false


func _build_torches() -> void:
	_torches.clear()
	for t in animator._torches:
		var n := Node3D.new()
		# On the south face of the wall, above the floor in front of it.
		n.position = Vector3(t.x + 0.5, 0.95, t.y + 1.02)
		_level.add_child(n)
		Models._mesh(n, Figure.cylinder(0.03, 0.02, 0.22, 6), Mats.solid(Color(0.25, 0.2, 0.18), 0.6, 0.5), Vector3(0, -0.06, 0.06), Vector3(-25, 0, 0))
		var flame := Node3D.new()
		flame.position = Vector3(0, 0.08, 0.1)
		n.add_child(flame)
		Models._mesh(flame, Figure.sphere(0.05, 8), Mats.solid(Color(1.0, 0.55, 0.15), 0.5, 0.0, 6.0), Vector3.ZERO, Vector3.ZERO, Vector3(1, 1.6, 1))
		Models._mesh(flame, Figure.sphere(0.03, 6), Mats.solid(Color(1.0, 0.9, 0.5), 0.5, 0.0, 8.0), Vector3(0, -0.01, 0.01))
		var light := OmniLight3D.new()
		light.light_color = Color(1.0, 0.6, 0.3)
		light.omni_range = 6.0
		light.omni_attenuation = 1.3
		light.light_energy = 1.8
		light.position = Vector3(0, 0.12, 0.35)
		light.shadow_bias = 0.08
		n.add_child(light)
		n.visible = false
		_torches.append([t, light, flame, n])


## Non-blocking dressing: banners and chains on the back walls, debris on the floor.
func _build_decor(d: Dungeon) -> void:
	var banner_colors := [Color(0.55, 0.1, 0.1), Color(0.12, 0.2, 0.5), Color(0.15, 0.35, 0.18), Color(0.4, 0.15, 0.45)]
	var torch_tiles := {}
	for t in animator._torches:
		torch_tiles[t] = true
	var decor := Node3D.new()
	decor.name = "Decor"
	_level.add_child(decor)
	_decor_nodes.clear()
	for t in _wall_tiles:
		if torch_tiles.has(t) or not d.is_walkable(t.x, t.y + 1):
			continue
		var h := WorldAnimator.tile_hash(t.x * 3 + 1, t.y * 7 + 2)
		var n := Node3D.new()
		n.position = Vector3(t.x + 0.5, 0, t.y + 1.0)
		if h % 9 == 0:
			# Hanging banner with a gold rod
			var col: Color = banner_colors[h % banner_colors.size()]
			Models._mesh(n, Figure.cylinder(0.015, 0.015, 0.5, 6), Models.metal(Models.GOLD), Vector3(0, 1.12, 0.03), Vector3(0, 0, 90))
			Models._mesh(n, Figure.box(Vector3(0.38, 0.62, 0.015)), Mats.solid(col, 0.95), Vector3(0, 0.8, 0.03))
			Models._mesh(n, Figure.cone(0.19, 0.12, 3), Mats.solid(col, 0.95), Vector3(0, 0.44, 0.03), Vector3(180, 0, 0), Vector3(1, 1, 0.08))
			Models._mesh(n, Figure.sphere(0.05, 8), Mats.solid(Models.GOLD, 0.4, 0.6), Vector3(0, 0.86, 0.045), Vector3.ZERO, Vector3(1, 1, 0.3))
		elif h % 9 == 4:
			# Shackles and chains
			for x in [-0.15, 0.15]:
				for i in 4:
					Models._mesh(n, Figure.cylinder(0.025, 0.025, 0.012, 8), Models.metal(Models.IRON), Vector3(x, 0.95 - i * 0.07, 0.02), Vector3(0, 90 * (i % 2), 90 * ((i + 1) % 2)))
		else:
			n.free()
			continue
		decor.add_child(n)
		_decor_nodes.append([t, n])
	var bone := Models.s(Models.BONE, 0.7)
	var pebble := Mats.textured("rock", 2.0, 1.0)
	for t in _floor_tiles:
		var h := WorldAnimator.tile_hash(t.x * 5 + 3, t.y * 11 + 1)
		if h % 7 != 0:
			continue
		var n := Node3D.new()
		n.position = Vector3(t.x + 0.2 + (h % 60) / 100.0, 0, t.y + 0.2 + (h / 60 % 60) / 100.0)
		n.rotation.y = (h % 628) / 100.0
		if h % 3 == 0:
			Models._mesh(n, Figure.capsule(0.012, 0.16), bone, Vector3(0, 0.012, 0), Vector3(90, 0, 0))
			Models._mesh(n, Figure.capsule(0.012, 0.12), bone, Vector3(0.05, 0.012, 0.03), Vector3(90, 50, 0))
		else:
			for i in 3:
				Models._mesh(n, Figure.sphere(0.03 + i * 0.012, 5), pebble, Vector3(i * 0.06 - 0.06, 0.01, (i % 2) * 0.05), Vector3.ZERO, Vector3(1, 0.5, 1))
		decor.add_child(n)
		_decor_nodes.append([t, n])


func _update_fog() -> void:
	var d := game.dungeon
	if d.visible == _last_vis and d.explored == _last_explored:
		return
	_last_vis = d.visible.duplicate()
	_last_explored = d.explored.duplicate()
	for layer in [[_floors, _floor_tiles, -0.1, Vector3.ONE], [_walls, _wall_tiles, WALL_H / 2, Vector3.ONE],
			[_low_walls, _low_wall_tiles, LOW_WALL_H / 2, Vector3.ONE], [_caps, _wall_tiles, WALL_H + 0.04, Vector3.ONE]]:
		var mm: MultiMesh = layer[0].multimesh
		var tiles: Array[Vector2i] = layer[1]
		for i in tiles.size():
			var t := tiles[i]
			var known := d.is_explored(t.x, t.y)
			var vis := d.is_visible(t.x, t.y)
			var shade := 0.88 + 0.12 * float(WorldAnimator.tile_hash(t.x, t.y) % 100) / 100.0
			var tone: Color = Color(0.82, 0.86, 0.95) if mm == _floors.multimesh else (Color(0.35, 0.33, 0.36) if mm == _caps.multimesh else Color(1.0, 0.93, 0.86))
			var c := Color(shade * tone.r, shade * tone.g, shade * tone.b) if vis else (FOG_MEMORY * tone if known else Color.BLACK)
			mm.set_instance_color(i, c)
			var scale := Vector3.ONE if known else Vector3.ZERO
			mm.set_instance_transform(i, Transform3D(Basis.from_scale(scale), Vector3(t.x + 0.5, layer[2], t.y + 0.5)))
	for dn in _decor_nodes:
		dn[1].visible = d.is_explored(dn[0].x, dn[0].y)
	var stairs := _level.get_node_or_null("Stairs")
	if stairs != null:
		stairs.visible = d.is_explored(d.stairs.x, d.stairs.y)


func _update_torches() -> void:
	var d := game.dungeon
	var focus := Vector2(animator.cam_x / PX, animator.cam_y / PX)
	# Only the torches nearest the action cast shadows (they're the expensive part on phones).
	var order := []
	for i in _torches.size():
		var t: Vector2i = _torches[i][0]
		var lit := d.is_explored(t.x, t.y)
		_torches[i][3].visible = lit
		if lit:
			order.append([focus.distance_squared_to(Vector2(t.x, t.y)), i])
	order.sort()
	for k in order.size():
		var i: int = order[k][1]
		var light: OmniLight3D = _torches[i][1]
		var flick := 1.0 + 0.12 * sin(_time * 11.0 + i * 1.7) + 0.08 * sin(_time * 23.7 + i)
		light.light_energy = 1.8 * flick
		light.shadow_enabled = k < MAX_SHADOW_TORCHES
		_torches[i][2].scale = Vector3(1, flick, 1)
	if _stairs_light != null:
		_stairs_light.light_energy = 1.4 * (1.0 + 0.1 * sin(_time * 3.0))


# =================================================================================
# Actors
# =================================================================================

func _sync_actors(dt: float) -> void:
	var d := game.dungeon
	var present := {}
	var leader := game.leader()
	for c in game.living_heroes() + game.monsters:
		var show: bool = c is Hero or d.is_visible(c.x, c.y) or c.ally
		if not show:
			if _actors.has(c):
				_actors[c]["fig"].visible = false
			present[c] = true
			continue
		present[c] = true
		if not _actors.has(c):
			_actors[c] = _spawn_actor(c)
		var a: Dictionary = _actors[c]
		var fig: Figure = a["fig"]
		fig.visible = true
		var pos := animator.display_pos(c)
		var target := px_to_world(pos)
		var prev := fig.position
		fig.position = target
		var anim = animator._anims.get(c)
		var moving: bool = anim != null and anim["moving"]
		var lunge := Vector3.ZERO
		var flash := 0.0
		if anim != null:
			flash = anim["flash"]
			if anim["lunge_t"] > 0.0:
				var k: float = sin((1.0 - anim["lunge_t"] / WorldAnimator.LUNGE_TIME) * PI)
				lunge = Vector3(anim["lunge_x"], 0, anim["lunge_y"]).normalized() * k
		# Face the way we're moving or attacking.
		var dir := Vector3(target.x - prev.x, 0, target.z - prev.z)
		if lunge.length() > 0.01:
			dir = lunge
		if dir.length() > 0.001:
			a["facing"] = atan2(dir.x, dir.z)
		fig.rotation.y = lerp_angle(fig.rotation.y, a["facing"], minf(1.0, dt * 12.0))
		var down: bool = c is Hero and c.is_unconscious()
		fig.animate(dt, moving, Basis(Vector3.UP, -fig.rotation.y) * lunge, flash, down)
		if a["light"] != null:
			var l: OmniLight3D = a["light"]
			l.visible = not down
			l.shadow_enabled = c == leader
			l.light_energy = 1.3 * (1.0 + 0.05 * sin(_time * 9.0 + c.x))
	for c in _actors.keys():
		if present.has(c):
			continue
		var a: Dictionary = _actors[c]
		_actors.erase(c)
		_dying.append({"fig": a["fig"], "t": 0.0})
	var still: Array = []
	for dying in _dying:
		dying["t"] += dt
		var fig: Figure = dying["fig"]
		fig.animate(dt, false, Vector3.ZERO, 0.0, true)
		fig.set_fade(clampf(1.0 - (dying["t"] - 0.4) / 0.6, 0.0, 1.0))
		if dying["t"] > 1.0:
			fig.queue_free()
		else:
			still.append(dying)
	_dying = still


func _spawn_actor(c: Creature) -> Dictionary:
	var fig: Figure
	var light: OmniLight3D = null
	if c is Hero:
		fig = Models.hero(c.race, c.char_class, c.gender)
		light = OmniLight3D.new()
		light.light_color = Color(1.0, 0.75, 0.5)
		light.omni_range = 6.0
		light.omni_attenuation = 1.1
		light.position = Vector3(0, 1.4, 0.3)
		light.shadow_bias = 0.08
		fig.add_child(light)
	else:
		fig = Models.monster(c.type)
		if c.ally:
			fig.set_tint(Color(0.3, 1.0, 0.5, 0.18))
	var ring := MeshInstance3D.new()
	var torus := TorusMesh.new()
	torus.inner_radius = 0.36
	torus.outer_radius = 0.44
	torus.rings = 24
	torus.ring_segments = 4
	ring.mesh = torus
	ring.scale = Vector3(1, 0.05, 1)
	ring.position = Vector3(0, 0.02, 0)
	ring.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	ring.visible = false
	fig.add_child(ring)
	add_child(fig)
	return {"fig": fig, "light": light, "ring": ring, "facing": 0.0}


# =================================================================================
# Props
# =================================================================================

func _sync_props(dt: float) -> void:
	var d := game.dungeon
	var want := {}
	for it in d.items:
		if d.is_explored(it["x"], it["y"]):
			var color: Color = Items.rarity_color(it["item"]) if it["item"] != null else Color.WHITE
			want["%d,%d,%s" % [it["x"], it["y"], it["kind"]]] = [Vector2i(it["x"], it["y"]), it["kind"], color]
	for p in game.corpses:
		if d.is_explored(p.x, p.y):
			want["%d,%d,BONES" % [p.x, p.y]] = [p, "BONES", Color.WHITE]
	for p in game.graves:
		want["%d,%d,GRAVE" % [p.x, p.y]] = [p, "GRAVE", Color.WHITE]
	for key in _props.keys():
		if not want.has(key):
			_props[key].queue_free()
			_props.erase(key)
	for key in want:
		if _props.has(key):
			continue
		var info: Array = want[key]
		var n := Models.prop(info[1], info[2])
		n.position = Vector3(info[0].x + 0.5, 0, info[0].y + 0.5)
		n.rotation.y = float(WorldAnimator.tile_hash(info[0].x, info[0].y) % 628) / 100.0
		if info[1] in ["TREASURE", "POTION"]:
			var l := OmniLight3D.new()
			l.light_color = info[2] if info[1] == "TREASURE" else Color(1.0, 0.3, 0.3)
			l.omni_range = 1.6
			l.light_energy = 1.2
			l.position = Vector3(0, 0.4, 0)
			n.add_child(l)
		add_child(n)
		_props[key] = n
	for key in _props:
		var spin = _props[key].get_node_or_null("Spin")
		if spin != null:
			spin.rotation.y += dt * 1.6
			spin.position.y = 0.35 + sin(_time * 2.5) * 0.04


# =================================================================================
# Effects
# =================================================================================

func _update_projectiles() -> void:
	var live := animator._projectiles.filter(func(p): return p["t"] >= 0.0)
	while _proj_pool.size() < live.size():
		var n := Node3D.new()
		var orb := MeshInstance3D.new()
		orb.mesh = Figure.sphere(0.09, 10)
		orb.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		n.add_child(orb)
		var arrow := MeshInstance3D.new()
		arrow.mesh = Figure.box(Vector3(0.025, 0.025, 0.42))
		arrow.material_override = Mats.solid(Color(0.75, 0.65, 0.45), 0.6)
		n.add_child(arrow)
		var light := OmniLight3D.new()
		light.omni_range = 3.0
		n.add_child(light)
		add_child(n)
		_proj_pool.append([n, orb, arrow, light])
	for i in _proj_pool.size():
		var entry: Array = _proj_pool[i]
		var n: Node3D = entry[0]
		if i >= live.size():
			n.visible = false
			continue
		var p: Dictionary = live[i]
		n.visible = true
		var k := minf(p["t"] / WorldAnimator.PROJECTILE_TIME, 1.0)
		var from := Vector3(p["fx"] / PX, 0.6, p["fy"] / PX)
		var to := Vector3(p["tx"] / PX, 0.6, p["ty"] / PX)
		var arc := sin(k * PI) * 0.25
		n.position = from.lerp(to, k) + Vector3(0, arc, 0)
		var col := WorldAnimator.color_of(p["kind"])
		var is_arrow: bool = p["kind"] == "ARROW"
		entry[1].visible = not is_arrow
		entry[2].visible = is_arrow
		entry[3].visible = not is_arrow
		if is_arrow:
			if from.distance_to(to) > 0.01:
				n.look_at(to + Vector3(0, arc, 0), Vector3.UP)
		else:
			entry[1].material_override = Mats.solid(col, 0.3, 0.0, 6.0)
			entry[3].light_color = col
			entry[3].light_energy = 2.5


func _update_particles() -> void:
	var list: Array = animator.particles.list
	var mm := _sparks.multimesh
	mm.instance_count = list.size()
	for i in list.size():
		var p: Array = list[i]
		var a := clampf(p[4] / p[5], 0.0, 1.0)
		var size: float = p[7] * 0.07
		var pos := Vector3(p[0] / PX, 0.45 + (p[10] - p[1]) / PX, (p[10] + p[11]) / PX)
		mm.set_instance_transform(i, Transform3D(Basis.from_scale(Vector3(size, size, size)), pos))
		var c: Color = p[6]
		mm.set_instance_color(i, Color(c.r * 2.0, c.g * 2.0, c.b * 2.0, a))


func _update_pulses() -> void:
	var pulses: Array = animator._pulses
	while _pulse_pool.size() < pulses.size():
		var l := OmniLight3D.new()
		l.omni_range = 3.5
		add_child(l)
		_pulse_pool.append(l)
	for i in _pulse_pool.size():
		var l := _pulse_pool[i]
		if i >= pulses.size():
			l.visible = false
			continue
		var p: Dictionary = pulses[i]
		var k: float = 1.0 - p["t"] / 0.4
		l.visible = true
		l.position = Vector3(p["x"] / PX, 0.8, p["y"] / PX)
		l.light_color = p["color"]
		l.light_energy = 4.0 * k
		l.omni_range = 2.5 + 1.5 * (1.0 - k)


# =================================================================================
# Ground markers
# =================================================================================

func _update_markers() -> void:
	var g := game
	var active := g.active_hero()
	# Squares the active hero can still walk to this turn.
	var tiles: Array = []
	if g.phase == Game.Phase.PLAYER_TURN and g.movement_left > 0 and g.armed_spell == "" and not g.armed_ranged \
			and not g.armed_potion and active != null and not active.is_unconscious() and not g.is_busy():
		tiles = reachable(g, active)
	var mm := _reach.multimesh
	mm.instance_count = tiles.size()
	for i in tiles.size():
		mm.set_instance_transform(i, Transform3D(Basis(), Vector3(tiles[i].x + 0.5, 0.015, tiles[i].y + 0.5)))
	var pulse := 0.6 + 0.4 * sin(_time * 5.0)
	for m in _ring_mats.values():
		m.albedo_color.a = 0.45 + 0.45 * pulse
	var feature := g.armed_feature
	var targeting := g.armed_spell != "" or g.armed_ranged or feature in ["CHALLENGE", "HUNTERS_MARK", "VOLLEY"]
	var ally_targeting: bool = g.armed_potion or feature == "LAY_ON_HANDS" or \
		(g.armed_spell != "" and Rules.SPELLS[g.armed_spell]["target"] == "ALLY")
	for c in _actors:
		var ring: MeshInstance3D = _actors[c]["ring"]
		var key := ""
		if c is Hero:
			if c == active:
				key = "gold"
			elif ally_targeting:
				key = "green"
		else:
			if c == g.current():
				key = "green" if c.ally else "red"
			elif not c.ally and targeting and active != null and in_range_of(g, active, c):
				key = "orange"
		ring.visible = key != ""
		if key != "":
			ring.material_override = _ring_mats[key]


## A rounded outline used for the move-range squares.
static func _outline_texture() -> Texture2D:
	const N := 64
	var img := Image.create(N, N, false, Image.FORMAT_RGBA8)
	for y in N:
		for x in N:
			var d := minf(minf(x, N - 1 - x), minf(y, N - 1 - y))
			var a := 0.0
			if d < 4.0:
				a = 1.0 - absf(d - 2.0) / 2.5
			a = maxf(a, 0.1)
			img.set_pixel(x, y, Color(1, 1, 1, clampf(a, 0.0, 1.0)))
	img.generate_mipmaps()
	return ImageTexture.create_from_image(img)


static func in_range_of(g: Game, h: Hero, m: Monster) -> bool:
	var range := 1
	if g.armed_spell != "":
		range = Rules.SPELLS[g.armed_spell]["range"]
	elif g.armed_feature == "CHALLENGE":
		range = 6
	elif g.armed_feature == "HUNTERS_MARK":
		range = 99
	else:
		range = h.ranged_weapon().get("range", 1)
	return h.distance_to(m) <= range and g.dungeon.has_line_of_sight(h.x, h.y, m.x, m.y)


## Squares the hero can reach with their remaining movement (allies can be passed, not stopped on).
static func reachable(g: Game, h: Hero) -> Array:
	var d := g.dungeon
	var start := Vector2i(h.x, h.y)
	var dist := {start: 0}
	var queue: Array[Vector2i] = [start]
	var head := 0
	while head < queue.size():
		var p := queue[head]
		head += 1
		var dp: int = dist[p]
		if dp >= g.movement_left:
			continue
		for dir in Dungeon.DIRS:
			var n := p + dir
			if dist.has(n) or not d.is_explored(n.x, n.y):
				continue
			if not d.can_step(p.x, p.y, n.x, n.y) or g.monster_at(n.x, n.y) != null:
				continue
			dist[n] = dp + 1
			queue.append(n)
	dist.erase(start)
	return dist.keys().filter(func(p): return g.hero_at(p.x, p.y) == null)
