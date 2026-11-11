class_name WorldView
extends Control
## Renders the dungeon at pixel resolution and upscales it: sprites, then a light map, then the
## lit scene with fog of war and glowing effects, then a final pass with bloom and colour grading.

const LIGHT_BANDS := 9.0

var animator := WorldAnimator.new()
var game: Game = null
var pixel_scale := 4

var _scene_vp: SubViewport
var _light_vp: SubViewport
var _lit_vp: SubViewport
var _scene_layer: Layer
var _light_layer: Layer
var _emissive_layer: Layer
var _ambient: ColorRect
var _lit_rect: TextureRect
var _final_rect: TextureRect
var _lit_mat: ShaderMaterial
var _final_mat: ShaderMaterial
var _vis_image: Image
var _vis_tex: ImageTexture
var _light_tex: ImageTexture
var _origin := Vector2.ZERO


## A canvas layer that draws one of the animator's lists.
class Layer extends Node2D:
	var view: WorldView
	var kind := ""

	func _draw() -> void:
		if view == null or view.game == null:
			return
		var a := view.animator
		var o := view._origin
		match kind:
			"scene":
				for s in a.sprites:
					_sprite(s, o, false)
			"light":
				var tex := view._light_tex
				for l in a.lights:
					var r: float = l[2]
					var c: Color = l[3]
					var k: float = l[4] * 0.5
					draw_texture_rect(tex, Rect2(l[0] - r - o.x, l[1] - r - o.y, r * 2, r * 2), false, Color(c.r * k, c.g * k, c.b * k))
			"emissive":
				# Hit flashes: a white silhouette added over the lit sprite.
				for s in a.sprites:
					if s[6] > 0.01:
						_sprite(s, o, true)
				for s in a.emissive:
					_sprite(s, o, false)

	func _sprite(s: Array, o: Vector2, flash: bool) -> void:
		var tex: Texture2D = s[0]
		var rect := Rect2(s[1] - o.x, s[2] - o.y, s[3], s[4])
		var col: Color = s[5]
		if flash:
			var f: float = s[6]
			col = Color(f, f, f, col.a)
		if s[7]:
			draw_set_transform(rect.get_center(), PI / 2)
			draw_texture_rect(tex, Rect2(-rect.size / 2, rect.size), false, col)
			draw_set_transform(Vector2.ZERO)
		else:
			draw_texture_rect(tex, rect, false, col)


func _ready() -> void:
	clip_contents = true
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	_light_tex = ImageTexture.create_from_image(_make_light_image())

	_scene_vp = _make_viewport(true)
	_scene_layer = _make_layer("scene", _scene_vp)

	_light_vp = _make_viewport(false)
	_ambient = ColorRect.new()
	_ambient.color = Color(WorldAnimator.AMBIENT.r * 0.5, WorldAnimator.AMBIENT.g * 0.5, WorldAnimator.AMBIENT.b * 0.5)
	_light_vp.add_child(_ambient)
	_light_layer = _make_layer("light", _light_vp)
	_light_layer.material = _additive()

	_lit_vp = _make_viewport(false)
	_lit_rect = TextureRect.new()
	_lit_rect.texture = _scene_vp.get_texture()
	_lit_rect.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
	_lit_mat = ShaderMaterial.new()
	_lit_mat.shader = load("res://shaders/lit.gdshader")
	_lit_mat.set_shader_parameter("light_tex", _light_vp.get_texture())
	_lit_rect.material = _lit_mat
	_lit_vp.add_child(_lit_rect)
	_emissive_layer = _make_layer("emissive", _lit_vp)
	_emissive_layer.material = _additive()

	_final_rect = TextureRect.new()
	_final_rect.texture = _lit_vp.get_texture()
	_final_rect.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
	_final_rect.stretch_mode = TextureRect.STRETCH_SCALE
	_final_rect.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	_final_rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_final_mat = ShaderMaterial.new()
	_final_mat.shader = load("res://shaders/final.gdshader")
	_final_rect.material = _final_mat
	add_child(_final_rect)


func _make_viewport(transparent: bool) -> SubViewport:
	var vp := SubViewport.new()
	vp.transparent_bg = transparent
	vp.disable_3d = true
	vp.render_target_update_mode = SubViewport.UPDATE_ALWAYS
	vp.canvas_item_default_texture_filter = Viewport.DEFAULT_CANVAS_ITEM_TEXTURE_FILTER_NEAREST
	vp.size = Vector2i(8, 8)
	add_child(vp)
	return vp


func _make_layer(kind: String, parent: Node) -> Layer:
	var l := Layer.new()
	l.view = self
	l.kind = kind
	parent.add_child(l)
	return l


static func _additive() -> CanvasItemMaterial:
	var m := CanvasItemMaterial.new()
	m.blend_mode = CanvasItemMaterial.BLEND_MODE_ADD
	return m


## Radial falloff with the stepped rings of pixel-art lighting.
static func _make_light_image() -> Image:
	const N := 128
	var img := Image.create(N, N, false, Image.FORMAT_RGBA8)
	for y in N:
		for x in N:
			var d := Vector2((x + 0.5) / N * 2.0 - 1.0, (y + 0.5) / N * 2.0 - 1.0).length()
			var f := clampf(1.0 - d, 0.0, 1.0)
			f = f * f * (3.0 - 2.0 * f)
			f = floorf(f * LIGHT_BANDS + 0.5) / LIGHT_BANDS
			img.set_pixel(x, y, Color(f, f, f, 1.0))
	return img


## Map square under a point in this control's coordinates.
func tile_at(local: Vector2) -> Vector2i:
	return animator.screen_to_tile(local)


## Advances animation by [param dt] seconds (0 while paused) and redraws.
func step(fx: Array, dt: float) -> void:
	if game == null:
		visible = false
		return
	visible = true
	animator.set_viewport(int(size.x), int(size.y), pixel_scale)
	animator.update(game, fx, dt)
	animator.build_frame(game)

	var sw := ceili(size.x / pixel_scale) + 2
	var sh := ceili(size.y / pixel_scale) + 2
	var cam_left := animator.cam_left()
	var cam_top := animator.cam_top()
	_origin = Vector2(floorf(cam_left) - 1.0, floorf(cam_top) - 1.0)
	for vp in [_scene_vp, _light_vp, _lit_vp]:
		if vp.size != Vector2i(sw, sh):
			vp.size = Vector2i(sw, sh)
	_ambient.size = Vector2(sw, sh)
	_lit_rect.size = Vector2(sw, sh)
	_update_vis()
	_lit_mat.set_shader_parameter("rect", Vector4(_origin.x, _origin.y, sw, sh))
	_lit_mat.set_shader_parameter("map_px", Vector2(game.dungeon.width * 16, game.dungeon.height * 16))
	_lit_mat.set_shader_parameter("vis_tex", _vis_tex)

	_final_rect.position = Vector2((_origin.x - cam_left) * pixel_scale, (_origin.y - cam_top) * pixel_scale)
	_final_rect.size = Vector2(sw, sh) * pixel_scale
	_final_mat.set_shader_parameter("texel", Vector2(1.0 / sw, 1.0 / sh))
	_final_mat.set_shader_parameter("view_origin", global_position)
	_final_mat.set_shader_parameter("view_size", size)

	_scene_layer.queue_redraw()
	_light_layer.queue_redraw()
	_emissive_layer.queue_redraw()


func _update_vis() -> void:
	var d := game.dungeon
	if _vis_image == null or _vis_image.get_width() != d.width or _vis_image.get_height() != d.height:
		_vis_image = Image.create(d.width, d.height, false, Image.FORMAT_RG8)
		_vis_tex = null
	var data := PackedByteArray()
	data.resize(d.width * d.height * 2)
	for i in d.width * d.height:
		data[i * 2] = 255 if d.visible[i] != 0 else 0
		data[i * 2 + 1] = 255 if d.explored[i] != 0 else 0
	_vis_image.set_data(d.width, d.height, false, Image.FORMAT_RG8, data)
	if _vis_tex == null:
		_vis_tex = ImageTexture.create_from_image(_vis_image)
	else:
		_vis_tex.update(_vis_image)
