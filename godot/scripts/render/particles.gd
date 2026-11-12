class_name Particles
extends RefCounted
## A small CPU particle system; positions in world pixels, drawn additively.
## Each particle is [x, y, vx, vy, life, max_life, color, size, gravity, drag, spawn_y, depth_jitter].
## In 3D, x runs east, spawn_y is the row, and (spawn_y - y) is height above the spawn point.

const MAX := 800

var rng := RandomNumberGenerator.new()
var list: Array = []


func _init(seed_value: int = 7) -> void:
	rng.seed = seed_value


func clear() -> void:
	list.clear()


func _rnd(a: float, b: float) -> float:
	return a + rng.randf() * (b - a)


func _add(x: float, y: float, vx: float, vy: float, life: float, color: Color, size: float = 1.0, gravity: float = 0.0, drag: float = 1.5) -> void:
	if list.size() >= MAX:
		return
	list.append([x, y, vx, vy, life, life, color, size, gravity, drag, y, _rnd(-4.0, 4.0)])


## Radial spray of sparks.
func burst(x: float, y: float, color: Color, n: int, speed: float = 60.0, gravity: float = 80.0, life: float = 0.4) -> void:
	for i in n:
		var a := _rnd(0.0, TAU)
		var s := _rnd(speed * 0.4, speed)
		_add(x, y, cos(a) * s, sin(a) * s - speed * 0.3, _rnd(life * 0.5, life), color, 1.0, gravity)


## Slowly rising motes (healing, holy light, level up).
func motes(x: float, y: float, color: Color, n: int, spread: float = 6.0) -> void:
	for i in n:
		_add(x + _rnd(-spread, spread), y + _rnd(-spread, spread), _rnd(-4.0, 4.0), _rnd(-30.0, -12.0),
			_rnd(0.6, 1.1), color, 1.0, -6.0, 0.5)


func ember(x: float, y: float) -> void:
	_add(x + _rnd(-1.5, 1.5), y, _rnd(-8.0, 8.0), _rnd(-35.0, -15.0), _rnd(0.35, 0.8),
		Color("ffb040") if rng.randi() % 2 == 0 else Color("ff6a20"), 1.0, -10.0, 1.0)


## Grey puff when something dies.
func dust(x: float, y: float, n: int) -> void:
	for i in n:
		var a := _rnd(0.0, TAU)
		var s := _rnd(8.0, 28.0)
		_add(x + _rnd(-4.0, 4.0), y + _rnd(-2.0, 4.0), cos(a) * s, sin(a) * s * 0.5 - 8.0, _rnd(0.5, 0.9),
			Color("6a6278"), 2.0, -8.0, 2.0)


func update(dt: float) -> void:
	var keep: Array = []
	for p in list:
		p[4] -= dt
		if p[4] <= 0.0:
			continue
		var damp: float = 1.0 / (1.0 + p[9] * dt)
		p[2] *= damp
		p[3] = p[3] * damp + p[8] * dt
		p[0] += p[2] * dt
		p[1] += p[3] * dt
		keep.append(p)
	list = keep
