extends Dice
## Returns the scripted values in order (each clamped to the die size), then falls back to 1s.

var queue: Array = []


func _init(values: Array = []) -> void:
	super(0)
	queue = values.duplicate()


func roll(sides: int) -> int:
	var v: int = queue.pop_front() if not queue.is_empty() else 1
	return clampi(v, 1, sides)
