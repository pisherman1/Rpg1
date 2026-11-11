class_name Atlas
extends RefCounted
## Builds every world sprite as a texture: hand-drawn sprites, composed heroes, and generated shapes.

const SIZE := 16

static var _textures := {}


static func texture(name: String) -> Texture2D:
	if _textures.is_empty():
		_build()
	return _textures.get(name)


static func hero_key(race: String, cls: String, gender: String = "MALE") -> String:
	return "hero_%s_%s_%s" % [cls, race, gender]


static func monster_key(type: String) -> String:
	return type.to_lower()


static func hero_texture(h: Hero) -> Texture2D:
	return texture(hero_key(h.race, h.char_class, h.gender))


## Halflings (and dwarves and therians, a little) are drawn shorter.
static func hero_scale(race: String) -> float:
	match race:
		"HALFLING": return 0.8
		"DWARF", "THERIAN": return 0.9
	return 1.0


static func sprite_image(name: String) -> Image:
	var img := Image.create(SIZE, SIZE, false, Image.FORMAT_RGBA8)
	_paint(img, Sprites.ART[name])
	return img


## Paints non-'.' characters of [param rows] onto [param img].
static func _paint(img: Image, rows: Array) -> void:
	for y in SIZE:
		var row: String = rows[y]
		for x in SIZE:
			var ch := row[x]
			if ch != ".":
				img.set_pixel(x, y, Sprites.PALETTE[ch])


static func _gender_overlay(cls: String, female: bool) -> String:
	return "%s_%s" % [cls.to_lower(), "female" if female else "male"]


## The class sprite, its male or female overlay, then race details (dwarven beards or braids, elven ears, fox mask).
static func hero_image(race: String, cls: String, gender: String = "MALE") -> Image:
	var img := sprite_image(cls.to_lower())
	_paint(img, Sprites.ART[_gender_overlay(cls, gender == "FEMALE")])
	var skin: Color = Sprites.PALETTE["s"]
	var outline: Color = Sprites.PALETTE["k"]
	match race:
		"DWARF":
			var beard := Color("b0602aff")
			if gender == "MALE":
				img.set_pixel(5, 6, beard)
				img.set_pixel(10, 6, beard)
				for x in range(5, 11):
					img.set_pixel(x, 7, beard)
				for x in range(6, 10):
					img.set_pixel(x, 8, beard)
				img.set_pixel(7, 9, beard)
				img.set_pixel(8, 9, beard)
			else:
				# Thick braids hanging past the shoulders.
				for y in range(6, 11):
					img.set_pixel(4, y, beard)
					img.set_pixel(11, y, beard)
				for p in [Vector2i(3, 7), Vector2i(12, 7), Vector2i(3, 9), Vector2i(12, 9)]:
					img.set_pixelv(p, outline)
				img.set_pixel(4, 11, Color("f2c641ff"))
				img.set_pixel(11, 11, Color("f2c641ff"))
		"THERIAN":
			_paint(img, Sprites.ART["fox"])
		"ELF":
			img.set_pixel(3, 4, outline)
			img.set_pixel(12, 4, outline)
			img.set_pixel(3, 5, skin)
			img.set_pixel(12, 5, skin)
			img.set_pixel(2, 4, outline)
			img.set_pixel(13, 4, outline)
	return img


static func _generated() -> Dictionary:
	var out := {}
	var shadow := Image.create(SIZE, SIZE, false, Image.FORMAT_RGBA8)
	var wall_shadow := Image.create(SIZE, SIZE, false, Image.FORMAT_RGBA8)
	var pixel := Image.create(SIZE, SIZE, false, Image.FORMAT_RGBA8)
	var orb := Image.create(SIZE, SIZE, false, Image.FORMAT_RGBA8)
	pixel.fill(Color.WHITE)
	for y in SIZE:
		for x in SIZE:
			# Soft drop shadow under creatures.
			var sx := x - 7.5
			var sy := y - 13.5
			var d := sx * sx / 36.0 + sy * sy / 4.0
			if d <= 1.0:
				shadow.set_pixel(x, y, Color(0, 0, 0, 0.45 * (1.0 - d * 0.6)))
			# Darkening strip on floor squares right under a wall.
			if y < 6:
				wall_shadow.set_pixel(x, y, Color(0, 0, 0, 0.55 * (1.0 - y / 6.0)))
			# Glowing orb for spell projectiles.
			var r := Vector2(x - 7.5, y - 7.5).length()
			if r < 1.6:
				orb.set_pixel(x, y, Color.WHITE)
			elif r < 3.2:
				orb.set_pixel(x, y, Color(1, 1, 1, 0.75))
			elif r < 4.6:
				orb.set_pixel(x, y, Color(1, 1, 1, 0.31))
	out["shadow"] = shadow
	out["wall_shadow"] = wall_shadow
	out["pixel"] = pixel
	out["orb"] = orb
	return out


static func _build() -> void:
	for name in Sprites.ART:
		_textures[name] = ImageTexture.create_from_image(sprite_image(name))
	for race in Rules.RACE_ORDER:
		for cls in Rules.CLASS_ORDER:
			for g in Rules.GENDERS:
				_textures[hero_key(race, cls, g)] = ImageTexture.create_from_image(hero_image(race, cls, g))
	var gen := _generated()
	for name in gen:
		_textures[name] = ImageTexture.create_from_image(gen[name])
