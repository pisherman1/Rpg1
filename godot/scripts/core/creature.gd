class_name Creature
extends RefCounted
## Anything that takes turns on the grid: heroes and monsters.

var name := ""
var x := 0
var y := 0
var hp := 0
## Dodge action: attacks against this creature have disadvantage until its next turn.
var dodging := false
## Guiding Bolt: the next attack roll against this creature has advantage.
var marked := false


func get_max_hp() -> int:
	return 1


func get_ac() -> int:
	return 10


## Squares per turn.
func get_speed() -> int:
	return 6


func ability_mod(_ability: String) -> int:
	return 0


func save_bonus(ability: String) -> int:
	return ability_mod(ability)


func initiative_bonus() -> int:
	return ability_mod("DEX")


func distance_to(other: Creature) -> int:
	return chebyshev(x, y, other.x, other.y)


func is_adjacent_to(other: Creature) -> bool:
	return distance_to(other) == 1


func move_to(nx: int, ny: int) -> void:
	x = nx
	y = ny


## Grid distance where a diagonal step counts as one square (5e default).
static func chebyshev(x1: int, y1: int, x2: int, y2: int) -> int:
	return maxi(absi(x1 - x2), absi(y1 - y2))
