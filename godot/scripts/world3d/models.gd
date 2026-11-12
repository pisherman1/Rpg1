class_name Models
extends RefCounted
## Builds the low-poly 3D models: heroes for every race/class/gender, monsters and dungeon props.

const SKIN := {
	"HUMAN": Color(0.86, 0.66, 0.52), "DWARF": Color(0.82, 0.6, 0.47), "ELF": Color(0.94, 0.82, 0.72),
	"HALFLING": Color(0.88, 0.67, 0.5), "THERIAN": Color(0.9, 0.72, 0.58),
}
const HAIR := {
	"HUMAN": Color(0.32, 0.2, 0.12), "DWARF": Color(0.72, 0.36, 0.15), "ELF": Color(0.92, 0.84, 0.55),
	"HALFLING": Color(0.4, 0.26, 0.14), "THERIAN": Color(0.85, 0.42, 0.14),
}
## Height and width multipliers per race.
const BUILD := {
	"HUMAN": [1.0, 1.0], "DWARF": [0.8, 1.2], "ELF": [1.04, 0.92], "HALFLING": [0.7, 0.95], "THERIAN": [0.92, 0.95],
}

const STEEL := Color(0.62, 0.64, 0.68)
const IRON := Color(0.35, 0.36, 0.38)
const GOLD := Color(0.95, 0.75, 0.3)
const LEATHER := Color(0.33, 0.22, 0.14)
const DARK_LEATHER := Color(0.18, 0.14, 0.12)
const WOOD := Color(0.42, 0.27, 0.15)
const BONE := Color(0.9, 0.87, 0.78)


static func s(c: Color, rough: float = 0.8) -> StandardMaterial3D:
	return Mats.solid(c, rough)


static func metal(c: Color) -> StandardMaterial3D:
	return Mats.solid(c, 0.35, 0.75)


static func glow(c: Color, energy: float = 2.5) -> StandardMaterial3D:
	return Mats.solid(c, 0.4, 0.0, energy)


# =================================================================================
# Rigged models (KayKit, CC0)
# =================================================================================

## Height (metres) and width multiplier per race; a human is about 1.15 m so heads read well from above.
const RACE_SIZE := {
	"HUMAN": [1.15, 1.0], "DWARF": [0.95, 1.18], "ELF": [1.2, 0.93], "HALFLING": [0.85, 0.95], "THERIAN": [1.08, 0.95],
}
const ONE_HAND := ["1H_Melee_Attack_Chop", "1H_Melee_Attack_Slice_Diagonal", "1H_Melee_Attack_Slice_Horizontal"]
const STAB := ["1H_Melee_Attack_Stab", "1H_Melee_Attack_Slice_Diagonal", "Dualwield_Melee_Attack_Stab"]
const TWO_HAND := ["2H_Melee_Attack_Chop", "2H_Melee_Attack_Slice"]
const UNARMED := ["Unarmed_Melee_Attack_Punch_A", "Unarmed_Melee_Attack_Punch_B"]
const KNIGHT_GEAR := ["1H_Sword_Offhand", "Badge_Shield", "Rectangle_Shield", "Round_Shield", "Spike_Shield", "1H_Sword", "2H_Sword"]
const BARBARIAN_GEAR := ["1H_Axe_Offhand", "Barbarian_Round_Shield", "1H_Axe", "2H_Axe", "Mug"]
const ROGUE_GEAR := ["Knife_Offhand", "1H_Crossbow", "2H_Crossbow", "Knife", "Throwable"]
const MAGE_GEAR := ["Spellbook", "Spellbook_open", "1H_Wand", "2H_Staff"]


static func _except(all: Array, keep: Array) -> Array:
	return all.filter(func(n): return not keep.has(n))


static func hero(race: String, cls: String, gender: String) -> Figure:
	if not ResourceLoader.exists(ModelFigure.DIR + "Knight.glb"):
		return hero_procedural(race, cls, gender)
	var female := gender == "FEMALE"
	var size: Array = RACE_SIZE[race]
	var h: float = size[0]
	var w: float = size[1]
	var dwarf_man := race == "DWARF" and not female
	var f: ModelFigure
	var head := ""
	match cls:
		"FIGHTER":
			# Knight: sword and shield; women fight bare-headed so you can tell them apart.
			f = ModelFigure.new("Knight.glb", h, w, _except(KNIGHT_GEAR, ["1H_Sword", "Round_Shield"]) + (["Knight_Helmet"] if female else []))
			head = "Knight_Head"
			f.attack_anims = ONE_HAND
			if female:
				f.swap_head("Rogue.glb", "Rogue_Head", ["Knight_Head"])
				head = "Rogue_Head"
		"ROGUE":
			f = ModelFigure.new("Rogue.glb" if female else "Rogue_Hooded.glb", h, w, _except(ROGUE_GEAR, ["Knife"]))
			head = "Rogue_Head" if female else "Rogue_Head_Hooded"
			f.attack_anims = STAB
		"WIZARD":
			f = ModelFigure.new("Mage.glb", h, w, _except(MAGE_GEAR, ["2H_Staff"]))
			head = "Mage_Head"
			f.attack_anims = ["2H_Melee_Attack_Stab", "1H_Melee_Attack_Chop"]
			f.cast_anim = "Spellcast_Shoot"
			if female:
				f.swap_head("Rogue.glb", "Rogue_Head", ["Mage_Head"])
				head = "Rogue_Head"
		_:
			# Cleric: a white-and-gold armoured priest with a shield and a holy sceptre.
			f = ModelFigure.new("Knight.glb", h, w, _except(KNIGHT_GEAR, ["Round_Shield"]) + ["Knight_Helmet"])
			f.tint(Color(1.0, 0.95, 0.78), ["Knight_Body", "Knight_ArmLeft", "Knight_ArmRight", "Knight_LegLeft", "Knight_LegRight", "Knight_Cape", "Round_Shield"])
			var sceptre := f.borrow("Mage.glb", "1H_Wand")
			if sceptre != null:
				f.tint(Color(1.0, 0.85, 0.45), ["1H_Wand"])
			f.attack_anims = ONE_HAND
			f.cast_anim = "Spellcast_Raise"
			f.swap_head("Rogue.glb" if female else "Mage.glb", "Rogue_Head" if female else "Mage_Head", ["Knight_Head"])
			head = "Rogue_Head" if female else "Mage_Head"
	if dwarf_man:
		f.swap_head("Barbarian.glb", "Barbarian_Head", [head])
	_race_parts(f, race, female, cls)
	return f


static func _race_parts(f: ModelFigure, race: String, female: bool, cls: String) -> void:
	var hb := f.head_box()
	var c := hb.get_center()
	var hs := hb.size
	match race:
		"ELF":
			var skin := s(SKIN["ELF"], 0.7)
			for side in [-1, 1]:
				f.bone_part("head", Figure.cone(hs.x * 0.09, hs.y * 0.42, 6), skin,
					Vector3(c.x + side * hs.x * 0.5, c.y + hs.y * 0.05, c.z - hs.z * 0.05), Vector3(0, 0, -side * 70))
		"DWARF":
			if female:
				var hair := s(HAIR["DWARF"], 0.9)
				for side in [-1, 1]:
					f.bone_part("head", Figure.capsule(hs.x * 0.09, hs.y * 0.75), hair,
						Vector3(c.x + side * hs.x * 0.42, c.y - hs.y * 0.45, c.z + hs.z * 0.05))
					f.bone_part("head", Figure.sphere(hs.x * 0.08, 8), metal(GOLD),
						Vector3(c.x + side * hs.x * 0.42, c.y - hs.y * 0.85, c.z + hs.z * 0.05))
		"THERIAN":
			var fox := s(Color(0.9, 0.45, 0.15), 0.8)
			var white := s(Color(0.96, 0.94, 0.9), 0.8)
			# Fox mask over the face: a pointed snout with a black nose.
			f.bone_part("head", Figure.cone(hs.x * 0.2, hs.z * 0.45, 8), fox,
				Vector3(c.x, c.y - hs.y * 0.08, c.z + hs.z * 0.5), Vector3(90, 0, 0))
			f.bone_part("head", Figure.sphere(hs.x * 0.06, 8), s(Color(0.05, 0.05, 0.05), 0.3),
				Vector3(c.x, c.y - hs.y * 0.08, c.z + hs.z * 0.73))
			f.bone_part("head", Figure.sphere(hs.x * 0.16, 10), white,
				Vector3(c.x, c.y - hs.y * 0.18, c.z + hs.z * 0.4), Vector3.ZERO, Vector3(1.2, 0.6, 1.0))
			if not (cls == "WIZARD" or (cls == "FIGHTER" and not female) or (cls == "ROGUE" and not female)):
				for side in [-1, 1]:
					f.bone_part("head", Figure.cone(hs.x * 0.14, hs.y * 0.38, 6), fox,
						Vector3(c.x + side * hs.x * 0.3, c.y + hs.y * 0.58, c.z), Vector3(0, 0, -side * 15))
			# Bushy tail from the hips.
			var hips := f.skel.get_bone_global_rest(f.skel.find_bone("hips")).origin
			var tail := f.bone_part("hips", Figure.capsule(hs.x * 0.14, hs.y * 0.9), s(Color(0.9, 0.45, 0.15), 0.95),
				hips + Vector3(0, -hs.y * 0.05, -hs.z * 0.55), Vector3(-55, 0, 0))
			f.bone_part("hips", Figure.sphere(hs.x * 0.13, 8), white, hips + Vector3(0, hs.y * 0.3, -hs.z * 0.95))
			if tail != null:
				f.tail = tail


static func monster(type: String) -> Figure:
	if type == "GIANT_RAT" or not ResourceLoader.exists(ModelFigure.DIR + "Knight.glb"):
		return monster_procedural(type)
	var f: ModelFigure
	match type:
		"SKELETON":
			f = ModelFigure.new("Skeleton_Warrior.glb", 1.15)
			f.attach_model("Skeleton_Blade.gltf", "handslot.r")
			f.attach_model("Skeleton_Shield_Small_A.gltf", "handslot.l")
			f.attack_anims = ONE_HAND
		"ZOMBIE":
			f = ModelFigure.new("Knight.glb", 1.15, 1.05, KNIGHT_GEAR + ["Knight_Helmet"])
			f.tint(Color(0.55, 0.72, 0.5))
			f.walk_anim = "Walking_C"
			f.attack_anims = UNARMED
		"GHOUL":
			f = ModelFigure.new("Skeleton_Rogue.glb", 1.12, 1.05)
			f.tint(Color(0.85, 0.72, 1.0))
			f.attack_anims = UNARMED
		"GOBLIN":
			f = ModelFigure.new("Rogue_Hooded.glb", 0.82, 0.95, _except(ROGUE_GEAR, ["Knife"]))
			f.tint(Color(0.55, 0.9, 0.45))
			f.attack_anims = STAB
			_ears(f, Color(0.45, 0.72, 0.35), 0.7)
		"KOBOLD":
			f = ModelFigure.new("Rogue_Hooded.glb", 0.75, 0.9, _except(ROGUE_GEAR, ["Knife"]))
			f.tint(Color(0.95, 0.55, 0.35))
			f.attack_anims = STAB
			_snout(f, Color(0.85, 0.42, 0.22))
			_monster_tail(f, Color(0.85, 0.42, 0.22))
		"ORC":
			f = ModelFigure.new("Barbarian.glb", 1.3, 1.15, _except(BARBARIAN_GEAR, ["1H_Axe", "Barbarian_Round_Shield"]) + ["Barbarian_Hat"])
			f.tint(Color(0.55, 0.78, 0.48))
			f.attack_anims = ONE_HAND
			_tusks(f)
		"GNOLL":
			f = ModelFigure.new("Barbarian.glb", 1.3, 0.95, _except(BARBARIAN_GEAR, ["2H_Axe"]))
			f.tint(Color(1.0, 0.82, 0.55))
			f.idle_anim = "2H_Melee_Idle"
			f.attack_anims = TWO_HAND
			_snout(f, Color(0.75, 0.6, 0.38))
		"BUGBEAR":
			f = ModelFigure.new("Barbarian.glb", 1.45, 1.2, _except(BARBARIAN_GEAR, ["1H_Axe"]))
			f.tint(Color(0.78, 0.58, 0.42))
			f.attack_anims = ONE_HAND
		"OGRE":
			f = ModelFigure.new("Barbarian.glb", 1.95, 1.4, _except(BARBARIAN_GEAR, ["2H_Axe"]) + ["Barbarian_Hat"])
			f.tint(Color(1.0, 0.86, 0.68))
			f.idle_anim = "2H_Melee_Idle"
			f.attack_anims = TWO_HAND
			f.walk_anim = "Walking_B"
			_tusks(f)
		_:
			return monster_procedural(type)
	return f


## The party's raised skeletons: a ragged minion that claws its way up out of the floor.
static func minion() -> Figure:
	if not ResourceLoader.exists(ModelFigure.DIR + "Skeleton_Minion.glb"):
		return monster_procedural("SKELETON")
	var f := ModelFigure.new("Skeleton_Minion.glb", 1.05)
	f.attach_model("Skeleton_Blade.gltf", "handslot.r")
	f.attack_anims = ONE_HAND
	f.spawn_anim = "Skeletons_Awaken_Floor"
	return f


static func _ears(f: ModelFigure, color: Color, size: float) -> void:
	var hb := f.head_box()
	var c := hb.get_center()
	var hs := hb.size
	for side in [-1, 1]:
		f.bone_part("head", Figure.cone(hs.x * 0.12 * size, hs.y * 0.9 * size, 6), s(color, 0.8),
			Vector3(c.x + side * hs.x * 0.52, c.y + hs.y * 0.05, c.z), Vector3(0, 0, -side * 80))


static func _snout(f: ModelFigure, color: Color) -> void:
	var hb := f.head_box()
	var c := hb.get_center()
	var hs := hb.size
	f.bone_part("head", Figure.capsule(hs.x * 0.15, hs.z * 0.55), s(color, 0.8),
		Vector3(c.x, c.y - hs.y * 0.12, c.z + hs.z * 0.55), Vector3(90, 0, 0), Vector3(1.0, 1.0, 0.8))
	f.bone_part("head", Figure.sphere(hs.x * 0.07, 8), s(Color(0.06, 0.05, 0.05), 0.3),
		Vector3(c.x, c.y - hs.y * 0.05, c.z + hs.z * 0.82))


static func _tusks(f: ModelFigure) -> void:
	var hb := f.head_box()
	var c := hb.get_center()
	var hs := hb.size
	for side in [-1, 1]:
		f.bone_part("head", Figure.cone(hs.x * 0.05, hs.y * 0.2, 6), s(Color(0.96, 0.92, 0.8), 0.5),
			Vector3(c.x + side * hs.x * 0.16, c.y - hs.y * 0.22, c.z + hs.z * 0.5))


static func _monster_tail(f: ModelFigure, color: Color) -> void:
	var hb := f.head_box()
	var hs := hb.size
	var hips := f.skel.get_bone_global_rest(f.skel.find_bone("hips")).origin
	f.tail = f.bone_part("hips", Figure.cylinder(hs.x * 0.04, hs.x * 0.14, hs.y * 1.2, 8), s(color, 0.8),
		hips + Vector3(0, -hs.y * 0.2, -hs.z * 0.7), Vector3(-70, 0, 0))


# =================================================================================
# Heroes (procedural fallback)
# =================================================================================

static func hero_procedural(race: String, cls: String, gender: String) -> Figure:
	var f := Figure.new()
	var b: Array = BUILD[race]
	var hgt: float = b[0]
	var wid: float = b[1]
	var female := gender == "FEMALE"
	var skin: Color = SKIN[race]
	var hair: Color = HAIR[race]

	var leg_len := 0.36
	var torso_len := 0.34
	var shoulder := 0.15 * (0.92 if female else 1.0)
	f.layout_humanoid(leg_len, torso_len, shoulder, 0.07)
	f.rig.scale = Vector3(wid, hgt, wid)

	# Colours by class
	var body := Color(0.5, 0.5, 0.5)
	var body_mat: Material
	var legs := DARK_LEATHER
	var trim := GOLD
	match cls:
		"FIGHTER":
			body_mat = metal(STEEL)
			legs = Color(0.25, 0.22, 0.2)
			trim = Color(0.5, 0.08, 0.08)
		"ROGUE":
			body = DARK_LEATHER
			body_mat = s(body)
			legs = Color(0.15, 0.13, 0.13)
			trim = Color(0.15, 0.3, 0.2)
		"WIZARD":
			body = Color(0.2, 0.26, 0.62) if not female else Color(0.42, 0.18, 0.55)
			body_mat = s(body, 0.9)
			legs = body
			trim = GOLD
		_:
			body = Color(0.88, 0.85, 0.78)
			body_mat = s(body, 0.85)
			legs = Color(0.55, 0.52, 0.48)
			trim = GOLD

	# Legs and boots
	for leg in [f.leg_l, f.leg_r]:
		f.part(leg, Figure.capsule(0.055, leg_len), s(legs), Vector3(0, -leg_len / 2, 0))
		f.part(leg, Figure.box(Vector3(0.09, 0.07, 0.14)), s(LEATHER), Vector3(0, -leg_len + 0.03, 0.025))
	# Torso
	var tw := 0.3 * (0.9 if female else 1.0)
	f.part(f.torso, Figure.capsule(tw / 2, torso_len + 0.08), body_mat, Vector3(0, torso_len / 2, 0), Vector3.ZERO, Vector3(1, 1, 0.72))
	f.part(f.torso, Figure.box(Vector3(tw * 0.95, 0.05, tw * 0.8)), s(LEATHER), Vector3(0, 0.04, 0))  # belt
	f.part(f.torso, Figure.box(Vector3(0.05, 0.035, 0.02)), metal(GOLD), Vector3(0, 0.04, tw * 0.4))  # buckle
	# Arms
	for arm in [f.arm_l, f.arm_r]:
		var sleeve: Material = metal(STEEL) if cls == "FIGHTER" else body_mat
		f.part(arm, Figure.capsule(0.045, 0.3), sleeve, Vector3(0, -0.14, 0))
		f.part(arm, Figure.sphere(0.04, 8), s(skin), Vector3(0, -0.3, 0))
	# Head
	f.part(f.head, Figure.capsule(0.035, 0.08), s(skin), Vector3(0, 0.02, 0))  # neck
	f.part(f.head, Figure.sphere(0.11), s(skin), Vector3(0, 0.12, 0))
	for x in [-0.04, 0.04]:
		f.part(f.head, Figure.sphere(0.015, 6), s(Color(0.08, 0.06, 0.06), 0.3), Vector3(x, 0.13, 0.098))
	_hair(f, race, female, hair, cls)
	_race_features(f, race, female, skin, hair)
	_class_gear(f, cls, female, body, trim)
	return f


static func _hair(f: Figure, race: String, female: bool, hair: Color, cls: String) -> void:
	var hat := cls == "WIZARD" or cls == "FIGHTER"
	if not hat:
		# Cap of hair over the top and back of the head.
		f.part(f.head, Figure.sphere(0.118), s(hair, 0.9), Vector3(0, 0.145, -0.012), Vector3.ZERO, Vector3(1, 0.85, 1))
	if female:
		if race == "DWARF":
			for x in [-0.08, 0.08]:
				f.part(f.head, Figure.capsule(0.03, 0.26), s(hair, 0.9), Vector3(x, -0.02, 0.02), Vector3(0, 0, 0))
				f.part(f.head, Figure.sphere(0.03, 6), metal(GOLD), Vector3(x, -0.15, 0.02))
		else:
			f.part(f.head, Figure.capsule(0.09, 0.32), s(hair, 0.9), Vector3(0, 0.02, -0.06), Vector3(12, 0, 0), Vector3(1, 1, 0.6))


static func _race_features(f: Figure, race: String, female: bool, skin: Color, hair: Color) -> void:
	match race:
		"DWARF":
			if not female:
				f.part(f.head, Figure.sphere(0.1, 10), s(hair, 0.95), Vector3(0, 0.05, 0.06), Vector3.ZERO, Vector3(1.05, 1.1, 0.8))
				f.part(f.head, Figure.cone(0.06, 0.12), s(hair, 0.95), Vector3(0, -0.06, 0.09), Vector3(180, 0, 0))
		"ELF":
			for side in [-1, 1]:
				f.part(f.head, Figure.cone(0.025, 0.12, 6), s(skin), Vector3(side * 0.11, 0.15, -0.01), Vector3(0, 0, -side * 60))
		"THERIAN":
			var fox := Color(0.88, 0.45, 0.15)
			var white := Color(0.95, 0.93, 0.88)
			# Fox mask: snout and cheeks
			f.part(f.head, Figure.cone(0.06, 0.12, 8), s(fox, 0.7), Vector3(0, 0.1, 0.13), Vector3(90, 0, 0))
			f.part(f.head, Figure.sphere(0.022, 6), s(Color(0.05, 0.05, 0.05), 0.3), Vector3(0, 0.1, 0.19))
			f.part(f.head, Figure.box(Vector3(0.2, 0.06, 0.02)), s(white, 0.7), Vector3(0, 0.1, 0.1))
			for side in [-1, 1]:
				f.part(f.head, Figure.cone(0.04, 0.1, 6), s(fox, 0.8), Vector3(side * 0.07, 0.25, 0), Vector3(0, 0, -side * 15))
				f.part(f.head, Figure.cone(0.016, 0.035, 6), s(Color(0.1, 0.07, 0.06)), Vector3(side * 0.077, 0.29, 0), Vector3(0, 0, -side * 15))
			# Bushy tail
			f.tail = Node3D.new()
			f.tail.position = Vector3(0, -0.02, -0.08)
			f.hips.add_child(f.tail)
			f.part(f.tail, Figure.capsule(0.06, 0.34), s(fox, 0.95), Vector3(0, 0.05, -0.15), Vector3(-60, 0, 0))
			f.part(f.tail, Figure.sphere(0.055, 8), s(white, 0.95), Vector3(0, 0.15, -0.29))


static func _class_gear(f: Figure, cls: String, female: bool, body: Color, trim: Color) -> void:
	match cls:
		"FIGHTER":
			f.part(f.head, Figure.sphere(0.125), metal(STEEL), Vector3(0, 0.15, -0.005), Vector3.ZERO, Vector3(1, 0.85, 1))
			f.part(f.head, Figure.box(Vector3(0.02, 0.07, 0.03)), metal(STEEL), Vector3(0, 0.1, 0.11))  # nasal guard
			if female:
				f.part(f.head, Figure.capsule(0.05, 0.2), s(HAIR_FOR_HELM, 0.9), Vector3(0, 0.05, -0.1), Vector3(20, 0, 0))
			f.part(f.torso, Figure.box(Vector3(0.14, 0.24, 0.015)), s(trim, 0.95), Vector3(0, 0.14, 0.108))  # tabard
			f.part(f.torso, Figure.box(Vector3(0.05, 0.05, 0.01)), metal(GOLD), Vector3(0, 0.2, 0.118))
			_sword(f.arm_r, f, 0.5)
			_shield(f.arm_l, f, Color(0.18, 0.28, 0.6), GOLD, false)
		"ROGUE":
			f.part(f.head, Figure.sphere(0.13), s(trim, 0.95), Vector3(0, 0.15, -0.02), Vector3.ZERO, Vector3(1, 1, 1.05))  # hood
			f.part(f.torso, Figure.box(Vector3(0.32, 0.4, 0.03)), s(trim, 0.95), Vector3(0, 0.15, -0.12), Vector3(-8, 0, 0))  # cloak
			_sword(f.arm_r, f, 0.32)
			# Bow and quiver on the back
			var bow := Node3D.new()
			bow.position = Vector3(0, 0.2, -0.13)
			bow.rotation_degrees = Vector3(0, 0, 25)
			f.torso.add_child(bow)
			for i in 5:
				var a := deg_to_rad(-60 + i * 30)
				f.part(bow, Figure.cylinder(0.012, 0.012, 0.13, 6), s(WOOD), Vector3(sin(a) * 0.04, cos(a) * 0.25 - 0.2, 0), Vector3(0, 0, -rad_to_deg(a)))
			f.part(f.torso, Figure.cylinder(0.04, 0.035, 0.24), s(LEATHER), Vector3(-0.08, 0.22, -0.13), Vector3(-10, 0, 20))
		"WIZARD":
			# Long robe skirt over the legs
			f.part(f.hips, Figure.cylinder(0.12, 0.2, 0.34), s(body, 0.9), Vector3(0, -0.15, 0))
			f.part(f.torso, Figure.box(Vector3(0.04, 0.3, 0.02)), s(trim), Vector3(0, 0.17, 0.105))
			# Pointed hat
			var hat := Color(body.r * 0.8, body.g * 0.8, body.b * 0.8)
			f.part(f.head, Figure.cylinder(0.2, 0.2, 0.02, 14), s(hat, 0.9), Vector3(0, 0.19, 0))
			f.part(f.head, Figure.cone(0.11, 0.3, 10), s(hat, 0.9), Vector3(0, 0.34, -0.02), Vector3(-10, 0, 0))
			f.part(f.head, Figure.cylinder(0.105, 0.112, 0.03, 12), s(trim), Vector3(0, 0.215, 0))
			if female:
				f.part(f.head, Figure.capsule(0.08, 0.3), s(Color(0.15, 0.1, 0.08), 0.9), Vector3(0, 0.03, -0.07), Vector3(15, 0, 0))
			# Staff with a glowing orb
			f.part(f.arm_r, Figure.cylinder(0.018, 0.022, 1.0, 6), s(WOOD), Vector3(0, -0.2, 0.05))
			f.part(f.arm_r, Figure.sphere(0.05, 10), glow(Color(0.45, 0.85, 1.0), 3.0), Vector3(0, 0.33, 0.05))
		_:
			# Cleric: tabard with a sun symbol, mace and round shield
			f.part(f.torso, Figure.box(Vector3(0.24, 0.32, 0.02)), s(Color(0.95, 0.93, 0.85), 0.8), Vector3(0, 0.15, 0.105))
			f.part(f.torso, Figure.cylinder(0.05, 0.05, 0.015, 12), glow(GOLD, 1.2), Vector3(0, 0.2, 0.12), Vector3(90, 0, 0))
			f.part(f.head, Figure.cylinder(0.12, 0.12, 0.025, 12), s(GOLD, 0.5), Vector3(0, 0.2, 0))  # circlet
			_mace(f.arm_r, f)
			_shield(f.arm_l, f, Color(0.92, 0.9, 0.82), GOLD, true)


const HAIR_FOR_HELM := Color(0.35, 0.22, 0.12)


static func _grip(arm: Node3D) -> Node3D:
	var g := Node3D.new()
	g.position = Vector3(0, -0.3, 0.02)
	g.rotation_degrees = Vector3(80, 0, 0)  # weapon points forward-up when the arm hangs
	arm.add_child(g)
	return g


static func _sword(arm: Node3D, f: Figure, length: float) -> void:
	var g := _grip(arm)
	f.part(g, Figure.cylinder(0.014, 0.014, 0.1, 6), s(LEATHER), Vector3(0, 0, 0))
	f.part(g, Figure.box(Vector3(0.12, 0.02, 0.03)), metal(GOLD), Vector3(0, 0.06, 0))
	f.part(g, Figure.box(Vector3(0.045, length, 0.01)), metal(Color(0.8, 0.82, 0.86)), Vector3(0, 0.07 + length / 2, 0))


static func _mace(arm: Node3D, f: Figure) -> void:
	var g := _grip(arm)
	f.part(g, Figure.cylinder(0.016, 0.016, 0.36, 6), s(WOOD), Vector3(0, 0.12, 0))
	f.part(g, Figure.sphere(0.06, 8), metal(IRON), Vector3(0, 0.32, 0))
	for a in [0, 90, 180, 270]:
		f.part(g, Figure.box(Vector3(0.02, 0.07, 0.13)), metal(IRON), Vector3(0, 0.32, 0), Vector3(0, a, 0))


static func _shield(arm: Node3D, f: Figure, face: Color, rim: Color, round_shield: bool) -> void:
	var holder := Node3D.new()
	holder.position = Vector3(0.06, -0.2, 0.05)
	holder.rotation_degrees = Vector3(0, 90, 90)
	arm.add_child(holder)
	if round_shield:
		f.part(holder, Figure.cylinder(0.16, 0.16, 0.03, 16), s(face, 0.6), Vector3.ZERO)
		f.part(holder, Figure.cylinder(0.17, 0.17, 0.02, 16), metal(rim), Vector3(0, -0.01, 0))
		f.part(holder, Figure.sphere(0.04, 8), metal(rim), Vector3(0, 0.02, 0))
	else:
		f.part(holder, Figure.box(Vector3(0.26, 0.03, 0.34)), s(face, 0.6), Vector3.ZERO)
		f.part(holder, Figure.box(Vector3(0.28, 0.02, 0.36)), metal(rim), Vector3(0, -0.01, 0))
		f.part(holder, Figure.box(Vector3(0.05, 0.035, 0.26)), metal(rim), Vector3(0, 0.005, 0))


# =================================================================================
# Monsters
# =================================================================================

static func monster_procedural(type: String) -> Figure:
	match type:
		"GIANT_RAT": return _rat()
		"KOBOLD": return _humanoid(0.62, 0.72, Color(0.78, 0.42, 0.2), Color(0.45, 0.25, 0.15), "spear", "kobold")
		"GOBLIN": return _humanoid(0.7, 0.8, Color(0.38, 0.6, 0.26), Color(0.38, 0.28, 0.18), "scimitar", "goblin")
		"SKELETON": return _humanoid(0.98, 0.7, BONE, BONE, "sword", "skeleton")
		"ZOMBIE": return _humanoid(1.0, 1.05, Color(0.45, 0.56, 0.4), Color(0.3, 0.25, 0.22), "", "zombie")
		"ORC": return _humanoid(1.12, 1.3, Color(0.36, 0.46, 0.3), Color(0.22, 0.2, 0.2), "axe", "orc")
		"GNOLL": return _humanoid(1.15, 1.1, Color(0.72, 0.56, 0.34), Color(0.35, 0.25, 0.15), "spear", "gnoll")
		"GHOUL": return _humanoid(1.0, 0.85, Color(0.62, 0.56, 0.72), Color(0.25, 0.2, 0.25), "", "ghoul")
		"BUGBEAR": return _humanoid(1.28, 1.4, Color(0.48, 0.32, 0.2), Color(0.3, 0.22, 0.15), "morningstar", "bugbear")
		"OGRE": return _humanoid(1.65, 1.75, Color(0.74, 0.6, 0.44), Color(0.4, 0.3, 0.2), "club", "ogre")
	return _humanoid(1.0, 1.0, Color.GRAY, Color.DIM_GRAY, "", "")


static func _humanoid(hgt: float, bulk: float, skin: Color, cloth: Color, weapon: String, kind: String) -> Figure:
	var f := Figure.new()
	var thin := kind == "skeleton"
	var limb := 0.025 if thin else 0.055
	f.layout_humanoid(0.36, 0.34, 0.15, 0.07)
	f.rig.scale = Vector3(bulk, hgt, bulk)
	var skin_mat := s(skin, 0.6 if thin else 0.75)
	for leg in [f.leg_l, f.leg_r]:
		f.part(leg, Figure.capsule(limb, 0.36), skin_mat, Vector3(0, -0.18, 0))
		f.part(leg, Figure.box(Vector3(0.08, 0.05, 0.12)), skin_mat, Vector3(0, -0.35, 0.03))
	if thin:
		# Spine and ribcage
		f.part(f.torso, Figure.cylinder(0.02, 0.02, 0.36, 6), skin_mat, Vector3(0, 0.17, -0.02))
		for i in 4:
			f.part(f.torso, Figure.cylinder(0.11 - i * 0.012, 0.11 - i * 0.012, 0.02, 10), skin_mat, Vector3(0, 0.3 - i * 0.06, 0), Vector3.ZERO, Vector3(1, 1, 0.7))
		f.part(f.torso, Figure.box(Vector3(0.2, 0.05, 0.1)), skin_mat, Vector3(0, 0.02, 0))
	else:
		f.part(f.torso, Figure.capsule(0.15, 0.42), skin_mat, Vector3(0, 0.17, 0), Vector3.ZERO, Vector3(1, 1, 0.75))
		f.part(f.torso, Figure.cylinder(0.16, 0.18, 0.18, 10), s(cloth, 0.95), Vector3(0, 0.03, 0), Vector3.ZERO, Vector3(1, 1, 0.8))
	for arm in [f.arm_l, f.arm_r]:
		f.part(arm, Figure.capsule(limb * (1.2 if kind == "ghoul" else 0.9), 0.32 if kind != "ghoul" else 0.4), skin_mat, Vector3(0, -0.15, 0))
		if kind == "ghoul":
			for c in 3:
				f.part(arm, Figure.cone(0.012, 0.07, 4), s(Color(0.9, 0.9, 0.85), 0.4), Vector3((c - 1) * 0.02, -0.4, 0.02), Vector3(180, 0, 0))
	# Head
	var head_mat := skin_mat
	f.part(f.head, Figure.sphere(0.11 if not thin else 0.1), head_mat, Vector3(0, 0.12, 0))
	var eye := s(Color(0.05, 0.03, 0.03), 0.3)
	var eye_size := 0.016
	match kind:
		"skeleton":
			eye = glow(Color(1.0, 0.3, 0.2), 2.0)
			eye_size = 0.025
			f.part(f.head, Figure.box(Vector3(0.12, 0.05, 0.08)), head_mat, Vector3(0, 0.04, 0.04))  # jaw
		"zombie":
			eye = glow(Color(0.8, 0.9, 0.4), 1.2)
			f.reaching = true
			f.walk_speed = 0.6
		"ghoul":
			eye = glow(Color(1.0, 0.85, 0.2), 2.5)
			f.hunch = 0.45
		"goblin":
			for side in [-1, 1]:
				f.part(f.head, Figure.cone(0.04, 0.16, 6), head_mat, Vector3(side * 0.13, 0.14, -0.01), Vector3(0, 0, -side * 75))
			f.part(f.head, Figure.cone(0.03, 0.07, 6), head_mat, Vector3(0, 0.11, 0.11), Vector3(90, 0, 0))
		"kobold":
			f.part(f.head, Figure.box(Vector3(0.1, 0.07, 0.14)), head_mat, Vector3(0, 0.08, 0.1))
			for side in [-1, 1]:
				f.part(f.head, Figure.cone(0.025, 0.09, 6), s(Color(0.9, 0.85, 0.7)), Vector3(side * 0.05, 0.22, -0.04), Vector3(-40, 0, 0))
			_add_tail(f, skin_mat, 0.05, 0.4)
		"orc":
			for side in [-1, 1]:
				f.part(f.head, Figure.cone(0.016, 0.06, 6), s(Color(0.95, 0.92, 0.8), 0.5), Vector3(side * 0.04, 0.08, 0.1), Vector3(0, 0, 0))
			for side in [-1, 1]:
				f.part(f.torso, Figure.sphere(0.075, 8), metal(IRON), Vector3(side * 0.16, 0.31, 0), Vector3.ZERO, Vector3(1.2, 0.8, 1))  # pauldrons
		"gnoll", "bugbear":
			var snout := 0.16 if kind == "gnoll" else 0.08
			f.part(f.head, Figure.box(Vector3(0.09, 0.08, snout)), head_mat, Vector3(0, 0.09, 0.08 + snout / 2))
			f.part(f.head, Figure.sphere(0.02, 6), eye, Vector3(0, 0.12, 0.08 + snout))
			for side in [-1, 1]:
				f.part(f.head, Figure.sphere(0.045, 8) if kind == "bugbear" else Figure.cone(0.04, 0.1, 6), head_mat, Vector3(side * 0.09, 0.23, -0.02))
		"ogre":
			f.part(f.torso, Figure.sphere(0.2, 12), skin_mat, Vector3(0, 0.12, 0.06), Vector3.ZERO, Vector3(1, 0.9, 0.9))  # belly
			f.part(f.head, Figure.box(Vector3(0.16, 0.06, 0.08)), head_mat, Vector3(0, 0.04, 0.07))
	for x in [-0.04, 0.04]:
		f.part(f.head, Figure.sphere(eye_size, 6), eye, Vector3(x, 0.135, 0.095))
	match weapon:
		"spear":
			var g := _grip(f.arm_r)
			f.part(g, Figure.cylinder(0.014, 0.014, 0.9, 6), s(WOOD), Vector3(0, 0.15, 0))
			f.part(g, Figure.cone(0.03, 0.1, 6), metal(IRON), Vector3(0, 0.64, 0))
		"scimitar":
			var g2 := _grip(f.arm_r)
			f.part(g2, Figure.cylinder(0.013, 0.013, 0.09, 6), s(LEATHER), Vector3.ZERO)
			f.part(g2, Figure.box(Vector3(0.05, 0.34, 0.01)), metal(Color(0.7, 0.7, 0.72)), Vector3(0.015, 0.22, 0), Vector3(0, 0, -8))
		"sword":
			_sword(f.arm_r, f, 0.4)
		"axe":
			var g3 := _grip(f.arm_r)
			f.part(g3, Figure.cylinder(0.018, 0.018, 0.5, 6), s(WOOD), Vector3(0, 0.15, 0))
			f.part(g3, Figure.box(Vector3(0.16, 0.14, 0.02)), metal(IRON), Vector3(0.06, 0.36, 0))
		"morningstar":
			var g4 := _grip(f.arm_r)
			f.part(g4, Figure.cylinder(0.018, 0.018, 0.45, 6), s(WOOD), Vector3(0, 0.15, 0))
			f.part(g4, Figure.sphere(0.07, 8), metal(IRON), Vector3(0, 0.4, 0))
			for a in 6:
				var d := Vector3(cos(a * TAU / 6), 0, sin(a * TAU / 6))
				f.part(g4, Figure.cone(0.02, 0.06, 4), metal(IRON), Vector3(0, 0.4, 0) + d * 0.08, Vector3(0, -a * 60, 90))
		"club":
			var g5 := _grip(f.arm_r)
			f.part(g5, Figure.cylinder(0.07, 0.03, 0.55, 8), s(WOOD, 0.95), Vector3(0, 0.25, 0))
	return f


static func _add_tail(f: Figure, mat: Material, radius: float, length: float) -> void:
	f.tail = Node3D.new()
	f.tail.position = Vector3(0, -0.02, -0.08)
	f.hips.add_child(f.tail)
	f.part(f.tail, Figure.cylinder(radius * 0.3, radius, length, 6), mat, Vector3(0, -0.05, -length / 2), Vector3(-75, 0, 0))


static func _rat() -> Figure:
	var f := Figure.new()
	f.beast = true
	f.walk_speed = 1.6
	var fur := s(Color(0.42, 0.33, 0.27), 0.95)
	var pink := s(Color(0.85, 0.6, 0.6), 0.7)
	f.hips.position = Vector3(0, 0.14, 0)
	f.torso.position = Vector3.ZERO
	f.part(f.torso, Figure.capsule(0.11, 0.42), fur, Vector3(0, 0.04, 0), Vector3(90, 0, 0))
	f.head.position = Vector3(0, 0.07, 0.22)
	f.part(f.head, Figure.cone(0.08, 0.18, 8), fur, Vector3(0, 0, 0.06), Vector3(90, 0, 0))
	f.part(f.head, Figure.sphere(0.02, 6), pink, Vector3(0, 0, 0.15))
	for side in [-1, 1]:
		f.part(f.head, Figure.sphere(0.04, 8), pink, Vector3(side * 0.06, 0.08, -0.02), Vector3.ZERO, Vector3(1, 1, 0.3))
		f.part(f.head, Figure.sphere(0.014, 6), glow(Color(0.9, 0.1, 0.1), 1.5), Vector3(side * 0.04, 0.04, 0.05))
	f.arm_l.visible = false
	f.arm_r.visible = false
	f.leg_l.position = Vector3(0.07, 0, 0.12)
	f.leg_r.position = Vector3(-0.07, 0, 0.12)
	f.leg_l2 = Node3D.new()
	f.leg_r2 = Node3D.new()
	f.leg_l2.position = Vector3(0.07, 0, -0.12)
	f.leg_r2.position = Vector3(-0.07, 0, -0.12)
	f.hips.add_child(f.leg_l2)
	f.hips.add_child(f.leg_r2)
	for leg in [f.leg_l, f.leg_r, f.leg_l2, f.leg_r2]:
		f.part(leg, Figure.capsule(0.022, 0.14), fur, Vector3(0, -0.07, 0))
	f.tail = Node3D.new()
	f.tail.position = Vector3(0, 0.04, -0.24)
	f.hips.add_child(f.tail)
	f.part(f.tail, Figure.cylinder(0.006, 0.02, 0.4, 6), pink, Vector3(0, -0.04, -0.19), Vector3(-80, 0, 0))
	f.rig.scale = Vector3(1.3, 1.3, 1.3)
	return f


# =================================================================================
# Props
# =================================================================================

static func prop(kind: String, rarity_color: Color = Color.WHITE) -> Node3D:
	var n := Node3D.new()
	match kind:
		"CHEST":
			var wood := Mats.textured("wood", 2.0, 0.8)
			_mesh(n, Figure.box(Vector3(0.5, 0.28, 0.34)), wood, Vector3(0, 0.14, 0))
			_mesh(n, Figure.cylinder(0.17, 0.17, 0.5, 12), wood, Vector3(0, 0.28, 0), Vector3(0, 0, 90), Vector3(1, 1, 1.0))
			for x in [-0.2, 0.2]:
				_mesh(n, Figure.box(Vector3(0.04, 0.3, 0.36)), metal(IRON), Vector3(x, 0.17, 0))
			_mesh(n, Figure.box(Vector3(0.08, 0.1, 0.04)), metal(GOLD), Vector3(0, 0.25, 0.18))
		"POTION":
			_mesh(n, Figure.sphere(0.1, 12), Mats.solid(Color(0.95, 0.15, 0.2), 0.1, 0.0, 1.6), Vector3(0, 0.12, 0))
			_mesh(n, Figure.cylinder(0.03, 0.035, 0.1, 8), Mats.solid(Color(0.8, 0.9, 0.95), 0.1), Vector3(0, 0.25, 0))
			_mesh(n, Figure.cylinder(0.035, 0.035, 0.04, 8), s(WOOD), Vector3(0, 0.31, 0))
		"GOLD":
			var gold := metal(GOLD)
			for i in 6:
				var a := i * 2.4
				_mesh(n, Figure.cylinder(0.06, 0.06, 0.02, 12), gold, Vector3(cos(a) * 0.1 * (i % 3), 0.01 + (i / 3) * 0.02, sin(a) * 0.1), Vector3(randf() * 10, 0, randf() * 10))
			_mesh(n, Figure.sphere(0.12, 10), s(LEATHER), Vector3(-0.12, 0.1, -0.05), Vector3.ZERO, Vector3(1, 0.9, 1))
		"TREASURE":
			var gem := Mats.solid(rarity_color, 0.15, 0.2, 2.2)
			var holder := Node3D.new()
			holder.name = "Spin"
			holder.position = Vector3(0, 0.35, 0)
			n.add_child(holder)
			_mesh(holder, Figure.sphere(0.11, 6), gem, Vector3.ZERO, Vector3.ZERO, Vector3(1, 1.5, 1))
			_mesh(n, Figure.cylinder(0.12, 0.16, 0.08, 8), Mats.textured("rock", 2.0), Vector3(0, 0.04, 0))
		"BONES":
			var bone := s(BONE, 0.7)
			_mesh(n, Figure.sphere(0.08, 8), bone, Vector3(0.05, 0.06, 0.05))
			for i in 4:
				_mesh(n, Figure.capsule(0.02, 0.3), bone, Vector3(-0.1 + i * 0.07, 0.025, -0.05 + (i % 2) * 0.12), Vector3(90, i * 47, 0))
		"GRAVE":
			var stone := Mats.textured("rock", 2.0)
			_mesh(n, Figure.box(Vector3(0.36, 0.5, 0.09)), stone, Vector3(0, 0.25, -0.2))
			_mesh(n, Figure.cylinder(0.18, 0.18, 0.09, 12), stone, Vector3(0, 0.5, -0.2), Vector3(90, 0, 0))
			_mesh(n, Figure.box(Vector3(0.4, 0.1, 0.6)), Mats.solid(Color(0.25, 0.2, 0.15), 1.0), Vector3(0, 0.05, 0.1))
	return n


static func _mesh(parent: Node3D, mesh: Mesh, mat: Material, pos: Vector3, rot_deg: Vector3 = Vector3.ZERO, scl: Vector3 = Vector3.ONE) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = mat
	mi.position = pos
	mi.rotation_degrees = rot_deg
	mi.scale = scl
	parent.add_child(mi)
	return mi
