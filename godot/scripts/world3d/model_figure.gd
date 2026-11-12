class_name ModelFigure
extends Figure
## A rigged, animated KayKit model (CC0, Kay Lousberg) behind the same interface as [Figure].
## All the humanoid models share one skeleton ("Rig_Medium"), so heads and gear can be mixed between them.

const DIR := "res://assets/models/kaykit/"
const LOOPING := ["Idle", "Walking_A", "Walking_B", "Walking_C", "Running_A", "2H_Melee_Idle", "Unarmed_Idle",
	"Spellcasting", "Lie_Idle", "Blocking", "Skeletons_Idle"]

static var _scenes := {}
static var _tinted := {}

var model: Node3D
var skel: Skeleton3D
var player: AnimationPlayer
var meshes: Array[MeshInstance3D] = []

## Animation choices for this character.
var idle_anim := "Idle"
var walk_anim := "Walking_A"
var attack_anims: Array = ["1H_Melee_Attack_Chop"]
var cast_anim := "Spellcast_Shoot"
var hit_anim := "Hit_A"
var down_anim := "Death_A"
var spawn_anim := ""

var _base := ""
var _busy_until := 0.0
var _clock := 0.0
var _was_lunge := false
var _was_flash := 0.0
var _was_down := false
var _scale_vec := Vector3.ONE


static func scene(file: String) -> PackedScene:
	if not _scenes.has(file):
		_scenes[file] = load(DIR + file)
	return _scenes[file]


## [param file] is the model to use; [param height] the target height in metres; [param hide] lists
## gear and body pieces to hide.
func _init(file: String, height: float, width: float = 1.0, hide: Array = []) -> void:
	super()
	model = scene(file).instantiate()
	rig.add_child(model)
	# The old procedural joints aren't used by models.
	hips.visible = false
	skel = model.find_child("Skeleton3D", true, false)
	player = model.find_child("AnimationPlayer", true, false)
	for mi in model.find_children("*", "MeshInstance3D", true, false):
		if mi.name in hide:
			mi.queue_free()
			continue
		_register(mi)
	# Normalise the model's size to the requested height (and widen or narrow it).
	var k := height / maxf(0.01, _measure_height())
	_scale_vec = Vector3(k * width, k, k * width)
	model.scale = _scale_vec
	_rig_height = height
	if player != null:
		for name in LOOPING:
			if player.has_animation(name):
				player.get_animation(name).loop_mode = Animation.LOOP_LINEAR
		_play(idle_anim, 0.0)


var _rig_height := 1.0


## Height in metres (portrait framing and overlays use this).
func model_height() -> float:
	return _rig_height


func _register(mi: MeshInstance3D) -> void:
	mi.material_overlay = _overlay
	meshes.append(mi)


func _measure_height() -> float:
	var top := -INF
	var bottom := INF
	for mi in meshes:
		if mi.mesh == null:
			continue
		var t := _transform_to_model(mi)
		var a := t * mi.get_aabb()
		top = maxf(top, a.end.y)
		bottom = minf(bottom, a.position.y)
	return top - bottom if top > bottom else 1.0


func _transform_to_model(n: Node3D) -> Transform3D:
	var t := Transform3D()
	var cur: Node = n
	while cur != null and cur != model:
		if cur is Node3D:
			t = cur.transform * t
		cur = cur.get_parent()
	return t


# --- Customisation --------------------------------------------------------------

## Replaces this model's head with the head mesh [param head_name] from [param file] (same skeleton).
func swap_head(file: String, head_name: String, old_heads: Array) -> void:
	for mi in meshes.duplicate():
		if mi.name in old_heads:
			meshes.erase(mi)
			mi.queue_free()
	var donor: Node3D = scene(file).instantiate()
	var head: MeshInstance3D = donor.find_child(head_name, true, false)
	if head != null and skel != null:
		head.get_parent().remove_child(head)
		skel.add_child(head)
		head.skeleton = NodePath("..")
		_register(head)
	donor.free()


## Multiplies the colours of every piece (or only the named pieces) by [param tint].
func tint(color: Color, only: Array = []) -> void:
	for mi in meshes:
		if not only.is_empty() and not (mi.name in only):
			continue
		if mi.mesh == null:
			continue
		for s in mi.mesh.get_surface_count():
			var src := mi.get_active_material(s)
			if src == null:
				continue
			var key := "%d|%s" % [src.get_instance_id(), color.to_html()]
			if not _tinted.has(key):
				var m: Material = src.duplicate()
				if m is BaseMaterial3D:
					m.albedo_color = m.albedo_color * color
				_tinted[key] = m
			mi.set_surface_override_material(s, _tinted[key])


## A node that follows the named bone, for adding hats, ears, beards and weapons.
func attach(bone: String) -> Node3D:
	var ba := BoneAttachment3D.new()
	ba.bone_name = bone
	skel.add_child(ba)
	var holder := Node3D.new()
	ba.add_child(holder)
	return holder


## Rest-pose box of the named mesh in the bone's space (to place features on the head).
func box_in_bone(mesh_name: String, bone: String) -> AABB:
	var mi: MeshInstance3D = null
	for m in meshes:
		if String(m.name).begins_with(mesh_name):
			mi = m
	var idx := skel.find_bone(bone)
	if mi == null or idx < 0:
		return AABB(Vector3(-0.3, 0, -0.3), Vector3(0.6, 0.6, 0.6))
	var rest := skel.get_bone_global_rest(idx)
	return rest.affine_inverse() * (_transform_to_skel(mi) * mi.get_aabb())


func _transform_to_skel(n: Node3D) -> Transform3D:
	var t := Transform3D()
	var cur: Node = n
	while cur != null and cur != skel:
		if cur is Node3D:
			t = cur.transform * t
		cur = cur.get_parent()
	return t


## Adds a mesh to a bone holder; extra parts get the flash overlay like the model.
func add_part(holder: Node3D, mesh: Mesh, mat: Material, pos: Vector3, rot_deg: Vector3 = Vector3.ZERO, scl: Vector3 = Vector3.ONE) -> MeshInstance3D:
	var mi := part(holder, mesh, mat, pos, rot_deg, scl)
	meshes.append(mi)
	return mi


## Hangs a separate model file (e.g. a weapon) from a bone.
func attach_model(file: String, bone: String, scale_k: float = 1.0) -> Node3D:
	var holder := attach(bone)
	var m: Node3D = scene(file).instantiate()
	m.scale = Vector3.ONE * scale_k
	holder.add_child(m)
	for mi in m.find_children("*", "MeshInstance3D", true, false):
		_register(mi)
	return m


# --- Animation --------------------------------------------------------------------

func _play(name: String, blend: float = 0.15, speed: float = 1.0) -> void:
	if player == null or not player.has_animation(name):
		return
	player.play(name, blend, speed)


func _one_shot(name: String, blend: float = 0.08) -> void:
	if player == null or not player.has_animation(name):
		return
	_base = ""
	player.play(name, blend)
	_busy_until = _clock + player.get_animation(name).length * 0.92


## Plays the casting or shooting animation (spells and ranged attacks).
func cast(ranged_weapon: bool) -> void:
	if _was_down:
		return
	_one_shot("1H_Ranged_Shoot" if ranged_weapon else cast_anim)


func animate(dt: float, moving: bool, lunge: Vector3, flash: float, down: bool) -> void:
	_clock += dt
	var lunging := lunge.length() > 0.01
	if player == null:
		return
	if down != _was_down:
		_was_down = down
		if down:
			_one_shot(down_anim, 0.1)
			_busy_until = INF
		else:
			_busy_until = 0.0
			_one_shot("Lie_StandUp")
	if not down:
		if lunging and not _was_lunge:
			_one_shot(attack_anims[randi() % attack_anims.size()])
		elif flash > 0.5 and _was_flash <= 0.5 and _clock >= _busy_until:
			_one_shot(hit_anim)
		if _clock >= _busy_until:
			var want := walk_anim if moving else idle_anim
			if want != _base:
				_base = want
				_play(want, 0.2, 1.25 if moving else 1.0)
	_was_lunge = lunging
	_was_flash = flash
	var f := clampf(flash, 0.0, 1.0)
	_overlay.albedo_color = Color(1, 1, 1, f * 0.7) if f > 0.01 else _base_overlay
	# A small step into the swing sells the hit.
	var l := lunge.length()
	rig.position = (lunge / l) * l * 0.18 if l > 0.001 else Vector3.ZERO


func set_fade(alpha: float) -> void:
	for mi in meshes:
		if is_instance_valid(mi):
			mi.transparency = 1.0 - alpha


## Plays the "rising from the floor" animation where the model has one (raised skeletons).
func play_spawn() -> void:
	if spawn_anim != "":
		_one_shot(spawn_anim, 0.0)


# --- Parts placed in skeleton space ----------------------------------------------

const HEADS := ["Knight_Head", "Barbarian_Head", "Mage_Head", "Rogue_Head", "Rogue_Head_Hooded"]
var _holders := {}


## Rest-pose box of the current head mesh, in skeleton space (+Y up, +Z forward).
func head_box() -> AABB:
	for mi in meshes:
		if String(mi.name) in HEADS or String(mi.name).ends_with("_Head") or String(mi.name).ends_with("_Skull"):
			return _transform_to_skel(mi) * mi.get_aabb()
	return AABB(Vector3(-0.5, 1.5, -0.5), Vector3(1, 1, 1))


## Adds a mesh that follows [param bone], positioned and rotated in skeleton space.
func bone_part(bone: String, mesh: Mesh, mat: Material, skel_pos: Vector3, rot_deg: Vector3 = Vector3.ZERO, scl: Vector3 = Vector3.ONE) -> MeshInstance3D:
	var idx := skel.find_bone(bone)
	if idx < 0:
		return null
	if not _holders.has(bone):
		_holders[bone] = attach(bone)
	var holder: Node3D = _holders[bone]
	var mi := add_part(holder, mesh, mat, Vector3.ZERO)
	var want := Transform3D(Basis.from_euler(rot_deg * PI / 180.0).scaled(scl), skel_pos)
	mi.transform = skel.get_bone_global_rest(idx).affine_inverse() * want
	return mi


## Moves a gear mesh from another model onto the same bone of this one (shared skeleton).
func borrow(file: String, mesh_name: String) -> MeshInstance3D:
	var donor: Node3D = scene(file).instantiate()
	var mi: MeshInstance3D = donor.find_child(mesh_name, true, false)
	var result: MeshInstance3D = null
	if mi != null and mi.get_parent() is BoneAttachment3D:
		var bone: String = mi.get_parent().bone_name
		var holder := attach(bone)
		var t := mi.transform
		mi.get_parent().remove_child(mi)
		holder.add_child(mi)
		mi.transform = t
		_register(mi)
		result = mi
	donor.free()
	return result
