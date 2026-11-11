class_name Items
extends RefCounted
## Magic items are plain dictionaries {"type": ItemType id, "bonus": +N}; these helpers describe them.


static func make(type: String, bonus: int = 0) -> Dictionary:
	return {"type": type, "bonus": bonus}


static func info(item: Dictionary) -> Dictionary:
	return Rules.ITEM_TYPES[item["type"]]


static func slot(item: Dictionary) -> String:
	return info(item)["slot"]


static func has_bonus(item: Dictionary) -> bool:
	return info(item)["fixed_rarity"] == null


static func rarity(item: Dictionary) -> String:
	var fixed = info(item)["fixed_rarity"]
	if fixed != null:
		return fixed
	match int(item["bonus"]):
		1: return "UNCOMMON"
		2: return "RARE"
		_: return "VERY_RARE"


static func rarity_color(item: Dictionary) -> Color:
	return Rules.RARITIES[rarity(item)]["color"]


static func item_name(item: Dictionary) -> String:
	if has_bonus(item):
		return "+%d %s" % [item["bonus"], info(item)["display_name"]]
	return info(item)["display_name"]


## Name as worn by [param hero], e.g. "+1 Longsword".
static func name_for(item: Dictionary, hero: Hero) -> String:
	match item["type"]:
		"MAGIC_WEAPON": return "+%d %s" % [item["bonus"], Rules.WEAPONS[hero.cls()["melee"]]["name"]]
		"MAGIC_ARMOR": return "+%d %s" % [item["bonus"], Rules.ARMORS[hero.cls()["armor"]]["name"]]
	return item_name(item)


static func description(item: Dictionary) -> String:
	return info(item)["description"].replace("+N", "+%d" % item["bonus"])


static func usable_by(item: Dictionary, hero: Hero) -> bool:
	return not info(item)["caster_only"] or hero.is_caster()


## [ability, score] for items that set a score ("your Constitution is 19"), or [].
static func sets_ability(item: Dictionary) -> Array:
	match item["type"]:
		"AMULET_OF_HEALTH": return ["CON", 19]
		"GAUNTLETS_OF_OGRE_POWER": return ["STR", 19]
		"HEADBAND_OF_INTELLECT": return ["INT", 19]
	return []


## Random treasure; deeper levels roll better rarities.
static func random_item(dice: Dice, depth: int, boss: bool = false) -> Dictionary:
	var r := dice.roll(100) + depth * 5 + (25 if boss else 0)
	var tier := "UNCOMMON"
	if r > 125:
		tier = "VERY_RARE"
	elif r > 85:
		tier = "RARE"
	var pick: Array = dice.pick(Rules.LOOT[tier])
	return make(pick[0], pick[1])


static func to_save(item: Dictionary) -> Array:
	return [item["type"], item["bonus"]]


static func from_save(v) -> Dictionary:
	return make(str(v[0]), int(v[1]))
