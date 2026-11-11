class_name Hero
extends Creature
## A player character: race, class, subclass, abilities, gear, spells and progression.

var race := "HUMAN"
var char_class := "FIGHTER"
var gender := "MALE"
## Scores with racial bonuses and ability score improvements, before magic items.
var base_abilities := {}

var level := 1
var xp := 0
var equipment := {}  # slot -> item
var hit_dice := 1
var spell_slots: Array = []
var max_spell_slots: Array = []
## Spells this hero can cast (ids).
var known_spells: Array = []

## Level-up choices still to make.
var pending_ability_points := 0
var pending_spells := 0
var subclass := ""
## Uses spent of limited features; absent means full.
var resources_used := {}

## Readied abilities that trigger on the next suitable hit or spell.
var smite_ready := false
var envenomed := false
var wrath_ready := false
var overchannel_ready := false

var potions := 2
var gold := 0
var second_wind_available := true
var action_surge_available := true
## Used by reactions (Uncanny Dodge, Warding Maneuver, Wrath of the Storm); refreshed each turn.
var reaction_available := true
## Bless: +1d4 to attack rolls until the fight ends.
var blessed := false

var death_successes := 0
var death_failures := 0
var stable := false
var dead := false


static func make(hero_name: String, hero_race: String, hero_class: String, abilities_with_race: Dictionary, hero_gender: String = "MALE") -> Hero:
	var h := Hero.new()
	h.name = hero_name
	h.race = hero_race
	h.char_class = hero_class
	h.gender = hero_gender
	h.base_abilities = abilities_with_race.duplicate()
	if h.is_caster():
		h.spell_slots = Rules.spell_slots_for(1)
		h.max_spell_slots = h.spell_slots.duplicate()
	h.known_spells = h.cls()["starting_spells"].duplicate()
	h.hp = h.get_max_hp()
	return h


## Rolled scores are assigned to abilities by class priority, then racial bonuses applied.
static func create(hero_name: String, hero_race: String, hero_class: String, rolled: Array, hero_gender: String = "MALE") -> Hero:
	var sorted := rolled.duplicate()
	sorted.sort()
	sorted.reverse()
	var scores := {}
	var priority: Array = Rules.CLASSES[hero_class]["priority"]
	for i in 6:
		scores[priority[i]] = sorted[i]
	return make(hero_name, hero_race, hero_class, with_bonuses(scores, Rules.RACES[hero_race]["bonuses"]), hero_gender)


## [param base_scores] in STR, DEX, CON, INT, WIS, CHA order, before racial bonuses.
static func with_scores(hero_name: String, hero_race: String, hero_class: String, base_scores: Array, hero_gender: String = "MALE") -> Hero:
	var scores := {}
	for i in 6:
		scores[Rules.ABILITIES[i]] = base_scores[i]
	return make(hero_name, hero_race, hero_class, with_bonuses(scores, Rules.RACES[hero_race]["bonuses"]), hero_gender)


static func with_bonuses(scores: Dictionary, bonuses: Dictionary) -> Dictionary:
	var out := {}
	for a in Rules.ABILITIES:
		out[a] = mini(20, int(scores.get(a, 10)) + int(bonuses.get(a, 0)))
	return out


func cls() -> Dictionary:
	return Rules.CLASSES[char_class]


func race_info() -> Dictionary:
	return Rules.RACES[race]


func is_caster() -> bool:
	return cls()["spell_ability"] != ""


# --- Abilities ----------------------------------------------------------------

## Effective scores: base scores, raised by items such as an Amulet of Health.
func abilities() -> Dictionary:
	var out := base_abilities.duplicate()
	for eq in equipment.values():
		var s := Items.sets_ability(eq)
		if not s.is_empty():
			out[s[0]] = maxi(out[s[0]], s[1])
	return out


func score(ability: String) -> int:
	return abilities()[ability]


func ability_mod(ability: String) -> int:
	return Rules.ability_mod(score(ability))


func _toughness() -> int:
	return 1 if race == "DWARF" else 0


## Fixed hit points per level (5e average) plus CON for every level, so CON changes apply retroactively.
func get_max_hp() -> int:
	var hd: int = cls()["hit_die"]
	return maxi(level, hd + (level - 1) * (hd / 2 + 1) + level * (ability_mod("CON") + _toughness()))


## Max HP gained per level after the first.
func hp_per_level() -> int:
	return maxi(1, cls()["hit_die"] / 2 + 1 + ability_mod("CON") + _toughness())


func proficiency() -> int:
	return 2 + (level - 1) / 4


func is_unconscious() -> bool:
	return hp <= 0 and not dead


func lucky() -> bool:
	return race == "HALFLING"


# --- Gear -------------------------------------------------------------------

func item(slot: String):
	return equipment.get(slot)


func has_item(type: String) -> bool:
	for i in equipment.values():
		if i["type"] == type:
			return true
	return false


func _bonus_in(slot: String) -> int:
	var i = equipment.get(slot)
	if i == null or not Items.usable_by(i, self):
		return 0
	return i["bonus"]


func weapon_bonus() -> int:
	return _bonus_in("WEAPON")


func armor_bonus() -> int:
	return _bonus_in("ARMOR")


func focus_bonus() -> int:
	return _bonus_in("FOCUS")


## Ring and Cloak of Protection each add +1 to AC and saving throws.
func _protection() -> int:
	var n := 0
	for i in equipment.values():
		if i["type"] in ["RING_OF_PROTECTION", "CLOAK_OF_PROTECTION"]:
			n += 1
	return n


func has_wound_closure() -> bool:
	return has_item("PERIAPT_OF_WOUND_CLOSURE")


func get_speed() -> int:
	return race_info()["speed"] + (1 if has_item("BOOTS_OF_STRIDING") else 0)


func _evasive() -> bool:
	return char_class == "ROGUE" and level >= 6


func get_ac() -> int:
	return Rules.armor_class(cls()["armor"], ability_mod("DEX")) + armor_bonus() + _protection() + \
		(1 if has_feature("SHIELD_WALL") else 0) + (1 if _evasive() else 0)


func save_bonus(ability: String) -> int:
	return ability_mod(ability) + (proficiency() if ability in cls()["saves"] else 0) + _protection()


func melee_weapon() -> Dictionary:
	return Rules.WEAPONS[cls()["melee"]]


## The bow (or crossbow) this hero shoots with; rangers upgrade to a longbow. Empty if none.
func ranged_weapon() -> Dictionary:
	if has_feature("ARCHERY"):
		return Rules.WEAPONS["LONGBOW"]
	var id: String = cls()["ranged"]
	return Rules.WEAPONS[id] if id != "" else {}


func ability_for(weapon: Dictionary) -> String:
	if weapon["ranged"]:
		return "DEX"
	if weapon["finesse"] and ability_mod("DEX") > ability_mod("STR"):
		return "DEX"
	return "STR"


func attack_bonus(weapon: Dictionary) -> int:
	return ability_mod(ability_for(weapon)) + proficiency() + weapon_bonus() + \
		(2 if weapon["ranged"] and has_feature("ARCHERY") else 0)


func damage_bonus(weapon: Dictionary) -> int:
	return ability_mod(ability_for(weapon)) + weapon_bonus()


func spell_mod() -> int:
	return ability_mod(cls()["spell_ability"]) if is_caster() else 0


func spell_attack_bonus() -> int:
	return spell_mod() + proficiency() + focus_bonus()


func spell_save_dc() -> int:
	return 8 + spell_mod() + proficiency() + focus_bonus()


## Rogue sneak attack dice: 1d6 at 1st level, +1d6 every odd level.
func sneak_attack_dice() -> int:
	return (level + 1) / 2


## Cantrips gain a die at 5th level.
func cantrip_dice() -> int:
	return 2 if level >= 5 else 1


## Fighters attack twice per Attack action from 5th level, three times at 10th.
func attacks_per_action() -> int:
	if char_class != "FIGHTER":
		return 1
	if level >= 10:
		return 3
	return 2 if level >= 5 else 1


func has_uncanny_dodge() -> bool:
	return char_class == "ROGUE" and level >= 5


func xp_for_next_level() -> int:
	return Rules.XP_TABLE[level] if level < Rules.XP_TABLE.size() else -1


# --- Subclass & resources ---------------------------------------------------------

func needs_subclass() -> bool:
	return level >= Rules.SUBCLASS_LEVEL and subclass == ""


func has_pending_choices() -> bool:
	return pending_ability_points > 0 or pending_spells > 0 or needs_subclass()


func has_feature(feature: String) -> bool:
	var f: Dictionary = Rules.FEATURES[feature]
	return subclass == f["subclass"] and level >= f["level"]


func max_uses(r: String) -> int:
	match r:
		"SMITE": return (3 if level >= 7 else 2) if has_feature("DIVINE_SMITE") else 0
		"LAY_ON_HANDS": return 5 * level if has_feature("LAY_ON_HANDS") else 0
		"ENVENOM": return 2 if has_feature("ENVENOM") else 0
		"PRESERVE_LIFE": return 1 if has_feature("PRESERVE_LIFE") else 0
		"DESTRUCTIVE_WRATH": return 1 if has_feature("DESTRUCTIVE_WRATH") else 0
		"WRATH_OF_THE_STORM": return maxi(1, ability_mod("WIS")) if has_feature("WRATH_OF_THE_STORM") else 0
		"OVERCHANNEL": return 1 if has_feature("OVERCHANNEL") else 0
		"ARCANE_RECOVERY": return 1 if has_feature("ARCANE_RECOVERY") else 0
		"INDOMITABLE": return 1 if char_class == "FIGHTER" and level >= 9 else 0
	return 0


func uses(r: String) -> int:
	return maxi(0, max_uses(r) - int(resources_used.get(r, 0)))


func spend(r: String, amount: int = 1) -> bool:
	if uses(r) < amount:
		return false
	resources_used[r] = int(resources_used.get(r, 0)) + amount
	return true


func choose_subclass(choice: String) -> bool:
	if not needs_subclass() or Rules.SUBCLASSES[choice]["char_class"] != char_class:
		return false
	subclass = choice
	_grant_subclass_spells()
	return true


## Domain spells that come with a subclass, known for free.
func _grant_subclass_spells() -> void:
	var grants := []
	if subclass == "PRIEST":
		grants = [["BLESS", 3], ["REVIVIFY", 5]]
	elif subclass == "TEMPEST":
		grants = [["THUNDERWAVE", 3], ["CALL_LIGHTNING", 5]]
	for g in grants:
		if level >= g[1] and not known_spells.has(g[0]):
			known_spells.append(g[0])


# --- Spells -------------------------------------------------------------------

func can_cast_any() -> bool:
	for s in spell_slots:
		if s > 0:
			return true
	return false


## Highest spell level this hero has slots for.
func max_spell_level() -> int:
	return max_spell_slots.size()


## Lowest slot level at or above [param min_level] with a slot left, or 0 for none.
func lowest_available_slot(min_level: int = 1) -> int:
	for lvl in range(maxi(1, min_level), spell_slots.size() + 1):
		if spell_slots[lvl - 1] > 0:
			return lvl
	return 0


func can_cast(spell: String) -> bool:
	return known_spells.has(spell) and (Rules.SPELLS[spell]["level"] == 0 or lowest_available_slot(Rules.SPELLS[spell]["level"]) > 0)


func spend_slot(slot_level: int) -> void:
	assert(spell_slots[slot_level - 1] > 0)
	spell_slots[slot_level - 1] -= 1


## Restores one spent slot of [param slot_level]; false if none are spent.
func recover_slot(slot_level: int) -> bool:
	if slot_level < 1 or slot_level > spell_slots.size() or spell_slots[slot_level - 1] >= max_spell_slots[slot_level - 1]:
		return false
	spell_slots[slot_level - 1] += 1
	return true


## Spells the hero could learn right now.
func learnable_spells() -> Array:
	var subclass_only := ["THUNDERWAVE", "CALL_LIGHTNING"] if char_class == "CLERIC" else []
	var out := []
	for s in cls()["spell_list"]:
		if not known_spells.has(s) and Rules.SPELLS[s]["level"] <= max_spell_level() and not subclass_only.has(s):
			out.append(s)
	return out


func learn_spell(spell: String) -> bool:
	if pending_spells <= 0 or not learnable_spells().has(spell):
		return false
	known_spells.append(spell)
	pending_spells -= 1
	return true


# --- Levelling ----------------------------------------------------------------

func is_ability_improvement_level(lvl: int) -> bool:
	return lvl in Rules.ASI_LEVELS or (char_class == "FIGHTER" and lvl == 6) or (char_class == "ROGUE" and lvl == 10)


## Ability score improvement: spend pending points (+2 to one or +1 to two), max 20. Keeps damage taken.
func improve_abilities(increases: Dictionary) -> bool:
	var total := 0
	for v in increases.values():
		if v < 0:
			return false
		total += v
	if total <= 0 or total > pending_ability_points:
		return false
	for a in increases:
		if base_abilities[a] + increases[a] > 20:
			return false
	var old_max := get_max_hp()
	for a in increases:
		base_abilities[a] += increases[a]
	pending_ability_points -= total
	if hp > 0:
		hp = clampi(hp + get_max_hp() - old_max, 1, get_max_hp())
	return true


## Equips [param it], returning whatever it replaced (or null). Keeps damage taken when max HP changes.
func equip(it: Dictionary):
	var old_max := get_max_hp()
	var slot := Items.slot(it)
	var previous = equipment.get(slot)
	equipment[slot] = it
	_adjust_hp_after(old_max)
	return previous


func unequip(slot: String):
	var old_max := get_max_hp()
	var it = equipment.get(slot)
	equipment.erase(slot)
	_adjust_hp_after(old_max)
	return it


func _adjust_hp_after(old_max: int) -> void:
	if hp > 0:
		hp = clampi(hp + get_max_hp() - old_max, 1, get_max_hp())


## Everything a hero gains on reaching [param lvl] (besides hit points), in words.
func level_gains(lvl: int) -> Array:
	var gains := []
	if lvl == Rules.SUBCLASS_LEVEL:
		gains.append("Choose a subclass" if subclass == "" else "%s path" % Rules.SUBCLASSES[subclass]["display_name"])
	if subclass != "":
		for f in Rules.features_of(subclass):
			if Rules.FEATURES[f]["level"] == lvl:
				gains.append(Rules.FEATURES[f]["title"])
	elif lvl == 7:
		gains.append("Subclass feature")
	match char_class:
		"FIGHTER":
			match lvl:
				2: gains.append("Action Surge: take an extra action once per rest")
				5: gains.append("Extra Attack: attack twice per Attack action")
				9: gains.append("Indomitable: reroll your first missed attack each long rest")
				10: gains.append("Extra Attack (2): attack three times per Attack action")
		"ROGUE":
			match lvl:
				2: gains.append("Cunning Action: Dash or Disengage as a bonus action")
				5: gains.append("Uncanny Dodge: halve the damage of a hit once per round")
				6: gains.append("Evasive: +1 AC")
			if lvl % 2 == 1 and lvl > 1:
				gains.append("Sneak Attack %dd6" % ((lvl + 1) / 2))
		_:
			var old_slots := Rules.spell_slots_for(lvl - 1)
			var new_slots := Rules.spell_slots_for(lvl)
			if new_slots.size() > old_slots.size():
				gains.append("%s-level spell slots" % _ordinal(new_slots.size()))
			else:
				gains.append("Spell slots %s" % "/".join(new_slots.map(func(n): return str(n))))
			if lvl == 5:
				gains.append("Cantrips deal double dice")
			gains.append("Learn a new spell")
	if is_ability_improvement_level(lvl):
		gains.append("Ability Score Improvement (+2)")
	if lvl == 5 or lvl == 9:
		gains.append("Proficiency bonus +%d" % (2 + (lvl - 1) / 4))
	return gains


func _ordinal(n: int) -> String:
	match n:
		1: return "1st"
		2: return "2nd"
		3: return "3rd"
	return "%dth" % n


## Adds XP and returns a message for each level gained.
func gain_xp(amount: int) -> Array:
	xp += amount
	var messages := []
	while level < Rules.MAX_LEVEL and xp >= Rules.XP_TABLE[level]:
		var old_max := get_max_hp()
		level += 1
		var gain := get_max_hp() - old_max
		if hp > 0:
			hp += gain
		hit_dice += 1
		if is_caster():
			var new_max := Rules.spell_slots_for(level)
			var slots := []
			for i in new_max.size():
				var had: int = spell_slots[i] if i < spell_slots.size() else 0
				var old: int = max_spell_slots[i] if i < max_spell_slots.size() else 0
				slots.append(had + new_max[i] - old)
			spell_slots = slots
			max_spell_slots = new_max
		if is_ability_improvement_level(level):
			pending_ability_points += 2
		if is_caster() and learnable_spells().size() > pending_spells:
			pending_spells += 1
		_grant_subclass_spells()
		var news := ["+%d max HP" % gain]
		for g in level_gains(level):
			news.append(g.split(":")[0])
		messages.append("%s reaches level %d! (%s)" % [name, level, ", ".join(news)])
	return messages


# --- Healing & rest -----------------------------------------------------------

func heal(amount: int) -> int:
	if dead:
		return 0
	var before := maxi(0, hp)
	hp = mini(get_max_hp(), before + amount)
	if hp > 0:
		death_successes = 0
		death_failures = 0
		stable = false
	return hp - before


## Brought back by Revivify.
func revive() -> void:
	dead = false
	hp = 1
	death_successes = 0
	death_failures = 0
	stable = false


func long_rest() -> void:
	resources_used.clear()
	hp = get_max_hp()
	hit_dice = mini(level, hit_dice + maxi(1, level / 2))
	spell_slots = max_spell_slots.duplicate()
	second_wind_available = true
	action_surge_available = true
	death_successes = 0
	death_failures = 0
	stable = false


## Spends hit dice until healed or out of dice; recovers short-rest features. Returns HP healed.
func short_rest(dice: Dice) -> int:
	var healed := 0
	while hit_dice > 0 and hp < get_max_hp():
		hit_dice -= 1
		var r := dice.roll(cls()["hit_die"]) * (2 if has_wound_closure() else 1)
		healed += heal(maxi(1, r + ability_mod("CON")))
	second_wind_available = true
	action_surge_available = true
	for r in resources_used.keys():
		if Rules.RESOURCES[r]:
			resources_used.erase(r)
	return healed


# --- Saving -------------------------------------------------------------------

func to_save() -> Dictionary:
	var eq := []
	for it in equipment.values():
		eq.append(Items.to_save(it))
	return {
		"name": name, "race": race, "class": char_class, "gender": gender,
		"abilities": Rules.ABILITIES.map(func(a): return base_abilities[a]),
		"level": level, "xp": xp, "hp": hp,
		"hitDice": hit_dice, "slots": spell_slots.duplicate(), "maxSlots": max_spell_slots.duplicate(),
		"spells": known_spells.duplicate(),
		"pendingAbilityPoints": pending_ability_points, "pendingSpells": pending_spells,
		"subclass": subclass if subclass != "" else null,
		"resourcesUsed": resources_used.duplicate(),
		"readied": [smite_ready, envenomed, wrath_ready, overchannel_ready],
		"equipment": eq,
		"potions": potions, "gold": gold,
		"secondWind": second_wind_available, "actionSurge": action_surge_available,
		"reaction": reaction_available, "blessed": blessed,
		"deathSuccesses": death_successes, "deathFailures": death_failures,
		"stable": stable, "dead": dead,
		"x": x, "y": y, "dodging": dodging, "marked": marked,
	}


## Rebuilds a hero from [method to_save] output; version 1 saves had +N bonuses instead of items.
static func from_save(h: Dictionary, version: int) -> Hero:
	var scores := {}
	for i in 6:
		scores[Rules.ABILITIES[i]] = int(h["abilities"][i])
	var hero := make(str(h["name"]), str(h["race"]), str(h["class"]), scores, str(h.get("gender", "MALE")) if h.get("gender") != null else "MALE")
	hero.level = int(h["level"])
	hero.xp = int(h["xp"])
	hero.spell_slots = Array(h["slots"]).map(func(v): return int(v))
	hero.max_spell_slots = Array(h["maxSlots"]).map(func(v): return int(v))
	if version >= 2:
		hero.known_spells = Array(h["spells"]).map(func(v): return str(v))
		hero.pending_ability_points = int(h["pendingAbilityPoints"])
		hero.pending_spells = int(h["pendingSpells"])
		for e in h["equipment"]:
			var it := Items.from_save(e)
			hero.equipment[Items.slot(it)] = it
		hero.reaction_available = bool(h["reaction"])
		hero.blessed = bool(h["blessed"])
		if h.get("subclass") != null:
			hero.subclass = str(h["subclass"])
		var used: Dictionary = h.get("resourcesUsed", {})
		for k in used:
			hero.resources_used[str(k)] = int(used[k])
		if h.get("readied") != null:
			var r: Array = h["readied"]
			hero.smite_ready = r[0]
			hero.envenomed = r[1]
			hero.wrath_ready = r[2]
			hero.overchannel_ready = r[3]
	else:
		if int(h.get("weaponBonus", 0)) > 0:
			hero.equipment["WEAPON"] = Items.make("MAGIC_WEAPON", int(h["weaponBonus"]))
		if int(h.get("armorBonus", 0)) > 0:
			hero.equipment["ARMOR"] = Items.make("MAGIC_ARMOR", int(h["armorBonus"]))
	hero.hp = int(h["hp"])
	hero.hit_dice = int(h["hitDice"])
	hero.potions = int(h["potions"])
	hero.gold = int(h["gold"])
	hero.second_wind_available = bool(h["secondWind"])
	hero.action_surge_available = bool(h["actionSurge"])
	hero.death_successes = int(h["deathSuccesses"])
	hero.death_failures = int(h["deathFailures"])
	hero.stable = bool(h["stable"])
	hero.dead = bool(h["dead"])
	hero.move_to(int(h["x"]), int(h["y"]))
	hero.dodging = bool(h["dodging"])
	hero.marked = bool(h["marked"])
	return hero
