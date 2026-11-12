class_name Mats
extends RefCounted
## Shared materials for the 3D world: textured stone and wood, plus cached solid colours for models.

static var _cache := {}


static func _tex(name: String) -> Texture2D:
	return load("res://assets/textures/%s.png" % name)


## Tiled PBR surface; [param uv_scale] repeats the texture per metre.
static func textured(name: String, uv_scale: float = 1.0, roughness: float = 0.9, vertex_tint: bool = false) -> StandardMaterial3D:
	var key := "tex:%s:%s:%s" % [name, uv_scale, vertex_tint]
	if _cache.has(key):
		return _cache[key]
	var m := StandardMaterial3D.new()
	m.albedo_texture = _tex(name + "_albedo")
	m.normal_enabled = true
	m.normal_texture = _tex(name + "_normal")
	m.normal_scale = 1.2
	m.roughness = roughness
	m.uv1_scale = Vector3(uv_scale, uv_scale, uv_scale)
	m.uv1_triplanar = true
	m.uv1_world_triplanar = true
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_LINEAR_WITH_MIPMAPS_ANISOTROPIC
	if vertex_tint:
		m.vertex_color_use_as_albedo = true
	_cache[key] = m
	return m


## A plain coloured surface for models. Metallic parts get a sheen, emissive parts glow.
static func solid(color: Color, roughness: float = 0.8, metallic: float = 0.0, emission: float = 0.0) -> StandardMaterial3D:
	var key := "solid:%s:%s:%s:%s" % [color.to_html(), roughness, metallic, emission]
	if _cache.has(key):
		return _cache[key]
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	m.roughness = roughness
	m.metallic = metallic
	if emission > 0.0:
		m.emission_enabled = true
		m.emission = color
		m.emission_energy_multiplier = emission
	_cache[key] = m
	return m


## Unshaded, always-on-top colour for UI-like markers in the world.
static func marker(color: Color, additive: bool = false, on_top: bool = false) -> StandardMaterial3D:
	var key := "marker:%s:%s:%s" % [color.to_html(), additive, on_top]
	if _cache.has(key):
		return _cache[key]
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.albedo_color = color
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.cull_mode = BaseMaterial3D.CULL_DISABLED
	if additive:
		m.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	if on_top:
		m.no_depth_test = true
		m.render_priority = 10
	_cache[key] = m
	return m


## Camera-facing glowing quad (particles, sparks). Colour comes from the instance/vertex colour.
static func spark() -> StandardMaterial3D:
	if _cache.has("spark"):
		return _cache["spark"]
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	m.vertex_color_use_as_albedo = true
	m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	m.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	m.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	m.billboard_keep_scale = true
	m.albedo_texture = _soft_dot()
	_cache["spark"] = m
	return m


static func _soft_dot() -> Texture2D:
	var img := Image.create(32, 32, false, Image.FORMAT_RGBA8)
	for y in 32:
		for x in 32:
			var d := Vector2(x - 15.5, y - 15.5).length() / 15.5
			var a := clampf(1.0 - d, 0.0, 1.0)
			img.set_pixel(x, y, Color(1, 1, 1, a * a))
	return ImageTexture.create_from_image(img)
