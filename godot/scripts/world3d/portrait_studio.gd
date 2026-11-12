class_name PortraitStudio
extends Node
## Renders 3D models into textures for the UI: cached still portraits of heroes and monsters,
## and live turntables of the heroes being built on the party screen.

const STILL_SIZE := Vector2i(160, 200)
const TURNTABLE_SIZE := Vector2i(320, 400)

var _stills := {}      # key -> SubViewport
var _turntables := {}  # slot -> {"vp", "fig", "key", "angle"}
var _pending := {}     # still viewport -> frames to wait before rendering


func hero_texture(race: String, cls: String, gender: String) -> Texture2D:
	var key := "h:%s:%s:%s" % [race, cls, gender]
	if not _stills.has(key):
		_stills[key] = _make_still(Models.hero(race, cls, gender), 1.2)
	return _stills[key].get_texture()


func monster_texture(type: String) -> Texture2D:
	var key := "m:" + type
	if not _stills.has(key):
		var fig := Models.monster(type)
		_stills[key] = _make_still(fig, maxf(0.6, fig.model_height()))
	return _stills[key].get_texture()


func creature_texture(c: Creature) -> Texture2D:
	if c is Hero:
		return hero_texture(c.race, c.char_class, c.gender)
	return monster_texture(c.type)


## A slowly turning, idling hero for party-screen card [param slot].
func turntable(slot: int, race: String, cls: String, gender: String) -> Texture2D:
	var key := "%s:%s:%s" % [race, cls, gender]
	var t = _turntables.get(slot)
	if t == null:
		var vp := _make_viewport(TURNTABLE_SIZE, 1.2)
		vp.render_target_update_mode = SubViewport.UPDATE_ALWAYS
		t = {"vp": vp, "fig": null, "key": "", "angle": 0.5 + slot * 0.8}
		_turntables[slot] = t
	if t["key"] != key:
		if t["fig"] != null:
			t["fig"].queue_free()
		var fig := Models.hero(race, cls, gender)
		t["vp"].add_child(fig)
		t["fig"] = fig
		t["key"] = key
	return t["vp"].get_texture()


## Pauses the turntables while the party screen isn't showing.
func set_turntables_active(active: bool) -> void:
	for t in _turntables.values():
		t["vp"].render_target_update_mode = SubViewport.UPDATE_ALWAYS if active else SubViewport.UPDATE_DISABLED


func _process(delta: float) -> void:
	for vp in _pending.keys():
		_pending[vp] -= 1
		if _pending[vp] <= 0:
			_pending.erase(vp)
			vp.render_target_update_mode = SubViewport.UPDATE_ONCE
	for t in _turntables.values():
		if t["fig"] == null or t["vp"].render_target_update_mode == SubViewport.UPDATE_DISABLED:
			continue
		t["angle"] += delta * 0.6
		var fig: Figure = t["fig"]
		fig.rotation.y = t["angle"]
		fig.animate(delta, false, Vector3.ZERO, 0.0, false)


func _make_still(fig: Figure, height: float) -> SubViewport:
	var vp := _make_viewport(STILL_SIZE, height)
	fig.rotation.y = 0.45
	fig.animate(0.0, false, Vector3.ZERO, 0.0, false)
	vp.add_child(fig)
	# Render once, a couple of frames from now, after the idle animation has posed the model.
	vp.render_target_update_mode = SubViewport.UPDATE_DISABLED
	_pending[vp] = 3
	return vp


func _make_viewport(size: Vector2i, height: float) -> SubViewport:
	var vp := SubViewport.new()
	vp.size = size
	vp.own_world_3d = true
	vp.transparent_bg = true
	vp.msaa_3d = Viewport.MSAA_4X
	add_child(vp)
	var env := WorldEnvironment.new()
	var e := Environment.new()
	e.background_mode = Environment.BG_CLEAR_COLOR
	e.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	e.ambient_light_color = Color(0.5, 0.5, 0.65)
	e.ambient_light_energy = 0.5
	e.tonemap_mode = Environment.TONE_MAPPER_ACES
	e.glow_enabled = true
	e.glow_intensity = 0.6
	env.environment = e
	vp.add_child(env)
	var key := DirectionalLight3D.new()
	key.light_color = Color(1.0, 0.88, 0.72)
	key.light_energy = 1.6
	key.rotation_degrees = Vector3(-35, 35, 0)
	vp.add_child(key)
	var rim := OmniLight3D.new()
	rim.light_color = Color(0.45, 0.6, 1.0)
	rim.light_energy = 2.5
	rim.omni_range = 4.0
	rim.position = Vector3(-0.8, 1.3 * height, -1.0)
	vp.add_child(rim)
	var cam := Camera3D.new()
	cam.fov = 30.0
	var aspect := float(size.x) / size.y
	var dist := 1.15 * height / tan(deg_to_rad(15.0)) * (0.46 if aspect < 1.0 else 0.5)
	vp.add_child(cam)
	cam.look_at_from_position(Vector3(0, 0.7 * height, dist), Vector3(0, 0.58 * height, 0), Vector3.UP)
	return vp
