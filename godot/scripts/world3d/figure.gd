class_name Figure
extends Node3D
## A jointed low-poly model (humanoid or beast) with procedural animation.
## Models face +Z. Build with the add_* helpers, then call [method animate] every frame.

## Joints. Humanoids use all of these; beasts use legs as front/back pairs.
var rig := Node3D.new()
var hips := Node3D.new()
var torso := Node3D.new()
var head := Node3D.new()
var arm_l := Node3D.new()
var arm_r := Node3D.new()
var leg_l := Node3D.new()
var leg_r := Node3D.new()
var tail: Node3D = null
## Extra legs for four-legged beasts (move opposite to leg_l / leg_r).
var leg_l2: Node3D = null
var leg_r2: Node3D = null

var beast := false
## Arms reach forward at rest (zombies).
var reaching := false
## Bent forward at the waist (ghouls).
var hunch := 0.0
var walk_speed := 1.0

var _phase := 0.0
var _time := 0.0
var _down := 0.0
var _overlay: StandardMaterial3D
var _base_overlay := Color(1, 1, 1, 0)
var _meshes: Array[MeshInstance3D] = []


func _init() -> void:
	add_child(rig)
	rig.add_child(hips)
	hips.add_child(torso)
	torso.add_child(head)
	torso.add_child(arm_l)
	torso.add_child(arm_r)
	hips.add_child(leg_l)
	hips.add_child(leg_r)
	_overlay = StandardMaterial3D.new()
	_overlay.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	_overlay.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_overlay.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	_overlay.albedo_color = Color(1, 1, 1, 0)


# --- Building -------------------------------------------------------------------

func part(parent: Node3D, mesh: Mesh, mat: Material, pos: Vector3, rot_deg: Vector3 = Vector3.ZERO, scl: Vector3 = Vector3.ONE) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.material_override = mat
	mi.position = pos
	mi.rotation_degrees = rot_deg
	mi.scale = scl
	mi.material_overlay = _overlay
	parent.add_child(mi)
	_meshes.append(mi)
	return mi


static func capsule(radius: float, height: float) -> CapsuleMesh:
	var m := CapsuleMesh.new()
	m.radius = radius
	m.height = maxf(height, radius * 2.0)
	m.radial_segments = 10
	m.rings = 3
	return m


static func sphere(radius: float, seg: int = 12) -> SphereMesh:
	var m := SphereMesh.new()
	m.radius = radius
	m.height = radius * 2.0
	m.radial_segments = seg
	m.rings = maxi(4, seg / 2)
	return m


static func box(size: Vector3) -> BoxMesh:
	var m := BoxMesh.new()
	m.size = size
	return m


static func cylinder(top: float, bottom: float, height: float, seg: int = 10) -> CylinderMesh:
	var m := CylinderMesh.new()
	m.top_radius = top
	m.bottom_radius = bottom
	m.height = height
	m.radial_segments = seg
	m.rings = 1
	return m


static func cone(radius: float, height: float, seg: int = 8) -> CylinderMesh:
	return cylinder(0.0, radius, height, seg)


## Sets the joint layout for a humanoid of the given proportions (metres).
func layout_humanoid(leg_len: float, torso_len: float, shoulder: float, hip: float) -> void:
	hips.position = Vector3(0, leg_len, 0)
	leg_l.position = Vector3(hip, 0, 0)
	leg_r.position = Vector3(-hip, 0, 0)
	torso.position = Vector3.ZERO
	head.position = Vector3(0, torso_len + 0.02, 0)
	arm_l.position = Vector3(shoulder, torso_len * 0.88, 0)
	arm_r.position = Vector3(-shoulder, torso_len * 0.88, 0)


## Overlay tint used when not flashing (e.g. ghostly green for the party's skeletons).
func set_tint(c: Color) -> void:
	_base_overlay = c


## Plays a casting or shooting gesture (models override this with real animations).
func cast(_ranged_weapon: bool) -> void:
	pass


## Height of the figure in metres.
func model_height() -> float:
	return rig.scale.y


## Fades the whole figure (death). 1 = solid.
func set_fade(alpha: float) -> void:
	for mi in _meshes:
		mi.transparency = 1.0 - alpha


# --- Animation ------------------------------------------------------------------

## [param lunge] is a horizontal direction scaled by 0..1 attack progress; [param flash] 0..1 hit flash.
func animate(dt: float, moving: bool, lunge: Vector3, flash: float, down: bool) -> void:
	_time += dt
	if moving:
		_phase += dt * 9.0 * walk_speed
	else:
		_phase = lerpf(_phase, roundf(_phase / PI) * PI, minf(1.0, dt * 10.0))
	var swing := sin(_phase) * (0.7 if moving else 0.0)
	var breathe := sin(_time * 2.2) * 0.015
	_down = move_toward(_down, 1.0 if down else 0.0, dt * 4.0)

	if beast:
		leg_l.rotation.x = swing
		leg_r.rotation.x = -swing
		if leg_l2 != null:
			leg_l2.rotation.x = -swing
			leg_r2.rotation.x = swing
		torso.rotation.x = 0.0
		head.rotation.x = sin(_time * 3.0) * 0.08
	else:
		leg_l.rotation.x = swing
		leg_r.rotation.x = -swing
		var rest := -1.3 if reaching else 0.0
		arm_l.rotation.x = rest - swing * 0.6 + breathe * 2.0
		arm_r.rotation.x = rest + swing * 0.6 - breathe * 2.0
		arm_l.rotation.z = 0.08
		arm_r.rotation.z = -0.08
		torso.rotation.x = hunch
		torso.scale = Vector3(1.0, 1.0 + breathe, 1.0)
		head.rotation.y = sin(_time * 0.7) * 0.15
	# Attack: lean in and swing the weapon arm.
	var l := lunge.length()
	if l > 0.001:
		var dir := lunge / l
		rig.position = Vector3(dir.x, 0, dir.z) * l * 0.28
		if not beast:
			arm_r.rotation.x = -2.2 * l
			torso.rotation.x = hunch + 0.35 * l
		else:
			head.rotation.x = 0.4 * l
	else:
		rig.position = Vector3.ZERO
	rig.position.y = absf(sin(_phase)) * 0.05 if moving else 0.0
	# Unconscious: topple over sideways.
	rig.rotation.z = _down * PI / 2 * 0.95
	rig.position.y += _down * 0.12
	if tail != null:
		tail.rotation.y = sin(_time * 4.0 + _phase) * 0.35
	var f := clampf(flash, 0.0, 1.0)
	_overlay.albedo_color = Color(1, 1, 1, f * 0.85) if f > 0.01 else _base_overlay
