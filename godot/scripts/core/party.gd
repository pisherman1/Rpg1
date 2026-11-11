class_name Party
extends RefCounted
## Premade heroes and parties, plus the party builder used on the party screen.

const SIZE := 4

const MALE_NAMES := ["Garrick", "Fenwick", "Orrin", "Dunmore", "Tobin", "Corvin", "Bram", "Halvard", "Aldous", "Rorik"]
const FEMALE_NAMES := ["Thora", "Mira", "Sable", "Kestrel", "Lyra", "Wren", "Isolde", "Nessa", "Brenna", "Elowen"]

## Premade heroes; "scores" are STR, DEX, CON, INT, WIS, CHA before racial bonuses ([] rolls 4d6-drop-lowest).
## Add your own variants here; they show up in the game automatically.
const HEROES := [
	{"name": "Brakka", "race": "DWARF", "class": "FIGHTER", "scores": [15, 12, 14, 8, 13, 10], "gender": "FEMALE"},
	{"name": "Pip", "race": "HALFLING", "class": "ROGUE", "scores": [10, 15, 14, 12, 13, 8], "gender": "MALE"},
	{"name": "Ilyra", "race": "ELF", "class": "WIZARD", "scores": [8, 14, 13, 15, 12, 10], "gender": "FEMALE"},
	{"name": "Aldric", "race": "HUMAN", "class": "CLERIC", "scores": [13, 10, 14, 8, 15, 12], "gender": "MALE"},
]

const PARTIES := [
	{"name": "The Classic Four", "members": [0, 1, 2, 3]},
]


static func names_for(gender: String) -> Array:
	return MALE_NAMES if gender == "MALE" else FEMALE_NAMES


static func other_gender(gender: String) -> String:
	return "FEMALE" if gender == "MALE" else "MALE"


## One slot on the party screen: either a premade or a custom build.
class Draft:
	var name: String
	var race: String
	var char_class: String
	var rolled: Array
	var gender: String
	## Fixed scores from a premade; empty means use [member rolled].
	var fixed_scores: Array = []

	func _init(n: String, r: String, c: String, scores: Array, g: String) -> void:
		name = n
		race = r
		char_class = c
		rolled = scores
		gender = g

	func build() -> Hero:
		if not fixed_scores.is_empty():
			return Hero.with_scores(name, race, char_class, fixed_scores, gender)
		return Hero.create(name, race, char_class, rolled, gender)

	func reroll(dice: Dice) -> void:
		rolled = []
		for i in 6:
			rolled.append(dice.ability_score())
		fixed_scores = []

	func apply(t: Dictionary, dice: Dice) -> void:
		name = t["name"]
		race = t["race"]
		char_class = t["class"]
		gender = t["gender"]
		if not t["scores"].is_empty():
			fixed_scores = t["scores"].duplicate()
		else:
			reroll(dice)


class Builder:
	var dice: Dice
	var drafts: Array = []

	func _init(d: Dice = null) -> void:
		dice = d if d != null else Dice.new()
		for i in Party.SIZE:
			var g := "MALE" if i % 2 == 0 else "FEMALE"
			var scores := []
			for j in 6:
				scores.append(dice.ability_score())
			drafts.append(Draft.new(random_name(g), Rules.RACE_ORDER[i % Rules.RACE_ORDER.size()],
				Rules.CLASS_ORDER[i % Rules.CLASS_ORDER.size()], scores, g))

	## Switches a hero's gender; a default name is swapped for one that fits.
	func toggle_gender(slot: int) -> void:
		var d: Draft = drafts[slot]
		var was_default := Party.names_for(d.gender).has(d.name)
		d.gender = Party.other_gender(d.gender)
		if was_default:
			d.name = random_name(d.gender)

	func apply_party(t: Dictionary) -> void:
		for i in t["members"].size():
			drafts[i].apply(Party.HEROES[t["members"][i]], dice)

	func apply_hero(slot: int, t: Dictionary) -> void:
		drafts[slot].apply(t, dice)

	func reroll(slot: int) -> void:
		drafts[slot].reroll(dice)

	func random_name(gender: String = "MALE") -> String:
		return dice.pick(Party.names_for(gender))

	func build() -> Array:
		return drafts.map(func(d): return d.build())
