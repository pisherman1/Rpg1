class_name Dice
extends RefCounted
## Dice rolling with a seedable random generator. Dice expressions are strings like "2d6+3".

enum Mode { NORMAL, ADVANTAGE, DISADVANTAGE }

var rng := RandomNumberGenerator.new()


func _init(seed_value: int = -1) -> void:
	if seed_value >= 0:
		rng.seed = seed_value
	else:
		rng.randomize()


## A random integer 1..sides. Tests override this to script rolls.
func roll(sides: int) -> int:
	return rng.randi_range(1, sides)


func roll_n(count: int, sides: int) -> int:
	var total := 0
	for i in count:
		total += roll(sides)
	return total


## Rolls a dice expression; a critical hit doubles the dice, not the flat bonus.
func roll_expr(expr: String, critical: bool = false) -> int:
	var e := parse(expr)
	var count: int = e["count"] * (2 if critical else 1)
	return roll_n(count, e["sides"]) + e["bonus"]


## Rolls a d20. With [param lucky] (halfling) a natural 1 is rerolled once.
func d20(mode: int = Mode.NORMAL, lucky: bool = false) -> Dictionary:
	var rolls: Array[int] = []
	var count := 1 if mode == Mode.NORMAL else 2
	for i in count:
		var r := roll(20)
		if lucky and r == 1:
			r = roll(20)
		rolls.append(r)
	var natural: int = rolls[0]
	if mode == Mode.ADVANTAGE:
		natural = rolls.max()
	elif mode == Mode.DISADVANTAGE:
		natural = rolls.min()
	return {"rolls": rolls, "natural": natural, "mode": mode}


## "d20 12" for a straight roll, "d20(4,17)" for advantage or disadvantage.
static func describe(r: Dictionary) -> String:
	if r["rolls"].size() == 1:
		return "d20 %d" % r["natural"]
	return "d20(%s)" % ",".join(r["rolls"].map(func(x): return str(x)))


static func mode_of(advantage: bool, disadvantage: bool) -> int:
	if advantage and not disadvantage:
		return Mode.ADVANTAGE
	if disadvantage and not advantage:
		return Mode.DISADVANTAGE
	return Mode.NORMAL


## Classic ability score generation: roll 4d6, drop the lowest die.
func ability_score() -> int:
	var dice_rolled: Array[int] = [roll(6), roll(6), roll(6), roll(6)]
	dice_rolled.sort()
	return dice_rolled[1] + dice_rolled[2] + dice_rolled[3]


func chance(numerator: int, denominator: int) -> bool:
	return roll(denominator) <= numerator


func pick(items: Array):
	return items[roll(items.size()) - 1]


## {"count", "sides", "bonus"} for "2d6+3".
static func parse(text: String) -> Dictionary:
	var t := text.replace(" ", "")
	var d_at := t.find("d")
	var count := int(t.substr(0, d_at))
	var rest := t.substr(d_at + 1)
	var bonus := 0
	var sign_at := maxi(rest.find("+"), rest.find("-"))
	if sign_at > 0:
		bonus = int(rest.substr(sign_at))
		rest = rest.substr(0, sign_at)
	return {"count": count, "sides": int(rest), "bonus": bonus}


static func expr(count: int, sides: int, bonus: int = 0) -> String:
	var s := "%dd%d" % [count, sides]
	if bonus > 0:
		s += "+%d" % bonus
	elif bonus < 0:
		s += str(bonus)
	return s


## The same expression with a different number of dice.
static func with_count(text: String, count: int) -> String:
	var e := parse(text)
	return expr(count, e["sides"], e["bonus"])


static func average(text: String) -> float:
	var e := parse(text)
	return e["count"] * (e["sides"] + 1) / 2.0 + e["bonus"]
