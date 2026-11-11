class_name Rules
extends RefCounted
## Static rules data: abilities, races, classes, gear, spells, monsters, items and subclasses.
## Ids are the same upper-case names the Android version uses, so save files are compatible.

const ABILITIES := ["STR", "DEX", "CON", "INT", "WIS", "CHA"]
const ABILITY_LABELS := {
	"STR": "Strength", "DEX": "Dexterity", "CON": "Constitution",
	"INT": "Intelligence", "WIS": "Wisdom", "CHA": "Charisma",
}

const GENDERS := {
	"MALE": {"label": "Male", "symbol": "♂"},
	"FEMALE": {"label": "Female", "symbol": "♀"},
}

## Speed and sight are in 5-foot squares.
const RACE_ORDER := ["HUMAN", "DWARF", "ELF", "HALFLING", "THERIAN"]
const RACES := {
	"HUMAN": {"display_name": "Human", "bonuses": {"STR": 1, "DEX": 1, "CON": 1, "INT": 1, "WIS": 1, "CHA": 1},
		"speed": 6, "sight": 8, "trait": "Versatile: +1 to every ability"},
	"DWARF": {"display_name": "Dwarf", "bonuses": {"CON": 2, "WIS": 1}, "speed": 5, "sight": 8,
		"trait": "Dwarven Toughness: +1 HP per level"},
	"ELF": {"display_name": "Elf", "bonuses": {"DEX": 2, "INT": 1}, "speed": 7, "sight": 10,
		"trait": "Keen Senses & Fleet of Foot: see farther, 35 ft speed"},
	"HALFLING": {"display_name": "Halfling", "bonuses": {"DEX": 2, "CHA": 1}, "speed": 5, "sight": 8,
		"trait": "Lucky: reroll natural 1s on d20s"},
	"THERIAN": {"display_name": "Therian", "bonuses": {"DEX": 2, "WIS": 1}, "speed": 6, "sight": 9,
		"trait": "Fox's Cunning: Stealth with advantage, keen senses"},
}

## range is in squares; 1 means melee.
const WEAPONS := {
	"LONGSWORD": {"name": "Longsword", "damage": "1d8", "damage_type": "slashing", "finesse": false, "ranged": false, "range": 1},
	"LIGHT_CROSSBOW": {"name": "Light Crossbow", "damage": "1d8", "damage_type": "piercing", "finesse": false, "ranged": true, "range": 8},
	"SHORTSWORD": {"name": "Shortsword", "damage": "1d6", "damage_type": "piercing", "finesse": true, "ranged": false, "range": 1},
	"SHORTBOW": {"name": "Shortbow", "damage": "1d6", "damage_type": "piercing", "finesse": false, "ranged": true, "range": 10},
	"LONGBOW": {"name": "Longbow", "damage": "1d8", "damage_type": "piercing", "finesse": false, "ranged": true, "range": 12},
	"QUARTERSTAFF": {"name": "Quarterstaff", "damage": "1d6", "damage_type": "bludgeoning", "finesse": false, "ranged": false, "range": 1},
	"MACE": {"name": "Mace", "damage": "1d6", "damage_type": "bludgeoning", "finesse": false, "ranged": false, "range": 1},
}

## max_dex -1 means no cap.
const ARMORS := {
	"CHAIN_MAIL_AND_SHIELD": {"name": "Chain Mail & Shield", "base": 16, "max_dex": 0, "shield": 2},
	"LEATHER": {"name": "Leather Armor", "base": 11, "max_dex": -1, "shield": 0},
	"MAGE_ARMOR": {"name": "Mage Armor", "base": 13, "max_dex": -1, "shield": 0},
	"SCALE_MAIL_AND_SHIELD": {"name": "Scale Mail & Shield", "base": 14, "max_dex": 2, "shield": 2},
}

const CLASS_ORDER := ["FIGHTER", "ROGUE", "WIZARD", "CLERIC"]
const CLASSES := {
	"FIGHTER": {
		"display_name": "Fighter", "hit_die": 10, "priority": ["STR", "CON", "DEX", "WIS", "CHA", "INT"],
		"saves": ["STR", "CON"], "melee": "LONGSWORD", "ranged": "LIGHT_CROSSBOW", "armor": "CHAIN_MAIL_AND_SHIELD",
		"spell_ability": "", "starting_spells": [], "spell_list": [],
		"features": "Second Wind, Action Surge (2nd), Extra Attack (5th), Indomitable (9th)",
	},
	"ROGUE": {
		"display_name": "Rogue", "hit_die": 8, "priority": ["DEX", "CON", "WIS", "INT", "CHA", "STR"],
		"saves": ["DEX", "INT"], "melee": "SHORTSWORD", "ranged": "SHORTBOW", "armor": "LEATHER",
		"spell_ability": "", "starting_spells": [], "spell_list": [],
		"features": "Sneak Attack, Aim, Cunning Action (2nd), Uncanny Dodge (5th), Evasive (6th)",
	},
	"WIZARD": {
		"display_name": "Wizard", "hit_die": 6, "priority": ["INT", "CON", "DEX", "WIS", "CHA", "STR"],
		"saves": ["INT", "WIS"], "melee": "QUARTERSTAFF", "ranged": "", "armor": "MAGE_ARMOR",
		"spell_ability": "INT", "starting_spells": ["FIRE_BOLT", "MAGIC_MISSILE"],
		"spell_list": ["FIRE_BOLT", "RAY_OF_FROST", "MAGIC_MISSILE", "BURNING_HANDS", "THUNDERWAVE", "SLEEP",
			"SCORCHING_RAY", "SHATTER", "ACID_ARROW", "FIREBALL", "LIGHTNING_BOLT", "ICE_STORM", "CONE_OF_COLD"],
		"features": "Arcane spells; learns a new spell every level",
	},
	"CLERIC": {
		"display_name": "Cleric", "hit_die": 8, "priority": ["WIS", "CON", "STR", "DEX", "CHA", "INT"],
		"saves": ["WIS", "CHA"], "melee": "MACE", "ranged": "", "armor": "SCALE_MAIL_AND_SHIELD",
		"spell_ability": "WIS", "starting_spells": ["SACRED_FLAME", "CURE_WOUNDS"],
		"spell_list": ["SACRED_FLAME", "TOLL_THE_DEAD", "GUIDING_BOLT", "CURE_WOUNDS", "HEALING_WORD",
			"INFLICT_WOUNDS", "BLESS", "PRAYER_OF_HEALING", "SPIRIT_GUARDIANS", "MASS_HEALING_WORD",
			"REVIVIFY", "FLAME_STRIKE", "MASS_CURE_WOUNDS"],
		"features": "Divine spells; learns a new spell every level",
	},
}

## Full-caster spell slots per spell level (index 0 = 1st level), for character levels 1-10.
const SPELL_SLOTS := [[2], [3], [4, 2], [4, 3], [4, 3, 2], [4, 3, 3], [4, 3, 3, 1], [4, 3, 3, 2], [4, 3, 3, 3, 1], [4, 3, 3, 3, 2]]

const MAX_LEVEL := 10
## A gentler curve than 5e's: roughly a level every half to two-thirds of a dungeon floor.
const XP_TABLE := [0, 80, 250, 550, 1000, 1600, 2350, 3250, 4300, 5500]
const ASI_LEVELS := [4, 8]
const SUBCLASS_LEVEL := 3
const REVIVIFY_COST := 300

const ITEM_SLOTS := ["WEAPON", "ARMOR", "FOCUS", "RING", "CLOAK", "NECK", "HANDS", "HEAD", "FEET"]
const ITEM_SLOT_LABELS := {
	"WEAPON": "Weapon", "ARMOR": "Armor", "FOCUS": "Focus", "RING": "Ring", "CLOAK": "Cloak",
	"NECK": "Neck", "HANDS": "Hands", "HEAD": "Head", "FEET": "Feet",
}
const RARITIES := {
	"UNCOMMON": {"label": "Uncommon", "color": Color("5ed35e")},
	"RARE": {"label": "Rare", "color": Color("5aa0ff")},
	"VERY_RARE": {"label": "Very rare", "color": Color("c070ff")},
}
const LOOT := {
	"UNCOMMON": [["MAGIC_WEAPON", 1], ["MAGIC_ARMOR", 1], ["WAND_OF_THE_WAR_MAGE", 1], ["RING_OF_PROTECTION", 0],
		["CLOAK_OF_PROTECTION", 0], ["PERIAPT_OF_WOUND_CLOSURE", 0], ["GAUNTLETS_OF_OGRE_POWER", 0],
		["HEADBAND_OF_INTELLECT", 0], ["BOOTS_OF_STRIDING", 0]],
	"RARE": [["MAGIC_WEAPON", 2], ["MAGIC_ARMOR", 2], ["WAND_OF_THE_WAR_MAGE", 2], ["AMULET_OF_HEALTH", 0]],
	"VERY_RARE": [["MAGIC_WEAPON", 3], ["MAGIC_ARMOR", 3], ["WAND_OF_THE_WAR_MAGE", 3]],
}

## Limited-use subclass resources; true means they recharge on a short rest.
const RESOURCES := {
	"SMITE": false, "LAY_ON_HANDS": false, "ENVENOM": true, "PRESERVE_LIFE": true,
	"DESTRUCTIVE_WRATH": true, "WRATH_OF_THE_STORM": false, "OVERCHANNEL": false,
	"ARCANE_RECOVERY": false, "INDOMITABLE": false,
}


static func ability_mod(score: int) -> int:
	return floori((score - 10) / 2.0)


static func format_mod(mod: int) -> String:
	return "+%d" % mod if mod >= 0 else str(mod)


static func spell_slots_for(level: int) -> Array:
	return SPELL_SLOTS[clampi(level - 1, 0, SPELL_SLOTS.size() - 1)].duplicate()


static func armor_class(armor_id: String, dex_mod: int) -> int:
	var a: Dictionary = ARMORS[armor_id]
	var dex := dex_mod if a["max_dex"] < 0 else mini(a["max_dex"], dex_mod)
	return a["base"] + dex + a["shield"]


static func is_undead(monster_type: String) -> bool:
	return monster_type in ["SKELETON", "ZOMBIE", "GHOUL"]


static func subclasses_for(char_class: String) -> Array:
	var out := []
	for id in SUBCLASSES:
		if SUBCLASSES[id]["char_class"] == char_class:
			out.append(id)
	return out


static func features_of(subclass_id: String) -> Array:
	var out := []
	for id in FEATURES:
		if FEATURES[id]["subclass"] == subclass_id:
			out.append(id)
	return out


static func spell_is_healing(spell_id: String) -> bool:
	return SPELLS[spell_id]["damage_type"] == "healing"


static func monster_melee(monster_type: String) -> Dictionary:
	for a in MONSTERS[monster_type]["attacks"]:
		if a["range"] <= 1:
			return a
	return MONSTERS[monster_type]["attacks"][0]


static func monster_ranged(monster_type: String) -> Dictionary:
	for a in MONSTERS[monster_type]["attacks"]:
		if a["range"] > 1:
			return a
	return {}

## Spells, monsters, items and subclasses below were generated from the Android rules engine.
const SPELLS := {
	"FIRE_BOLT": {
		"save_ability": null,
		"upcast_dice": 0,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Fire Bolt",
		"level": 0,
		"target": "ENEMY",
		"range": 10,
		"dice": "1d10",
		"damage_type": "fire",
		"description": "Ranged spell attack: 1d10 fire.",
		"short_name": "Fire Bolt",
	},
	"RAY_OF_FROST": {
		"save_ability": null,
		"upcast_dice": 0,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Ray of Frost",
		"level": 0,
		"target": "ENEMY",
		"range": 10,
		"dice": "1d8",
		"damage_type": "cold",
		"description": "Ranged spell attack: 1d8 cold.",
		"short_name": "Frost Ray",
	},
	"MAGIC_MISSILE": {
		"save_ability": null,
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Magic Missile",
		"level": 1,
		"target": "ENEMY",
		"range": 10,
		"dice": "1d4+1",
		"damage_type": "force",
		"description": "3 darts of 1d4+1 force that never miss. +1 dart per higher slot.",
		"short_name": "Missile",
	},
	"BURNING_HANDS": {
		"save_ability": "DEX",
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Burning Hands",
		"level": 1,
		"target": "ADJACENT_AREA",
		"range": 1,
		"dice": "3d6",
		"damage_type": "fire",
		"description": "Adjacent foes: DEX save or 3d6 fire, half on a save. +1d6 per higher slot.",
		"short_name": "Burn Hands",
	},
	"THUNDERWAVE": {
		"save_ability": "CON",
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Thunderwave",
		"level": 1,
		"target": "ADJACENT_AREA",
		"range": 1,
		"dice": "2d8",
		"damage_type": "thunder",
		"description": "Adjacent foes: CON save or 2d8 thunder, half on a save. +1d8 per higher slot.",
		"short_name": "Thunderwave",
	},
	"SLEEP": {
		"save_ability": null,
		"upcast_dice": 2,
		"radius": 2,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Sleep",
		"level": 1,
		"target": "ENEMY_AREA",
		"range": 10,
		"dice": "5d8",
		"damage_type": "sleep",
		"description": "5d8 HP worth of foes near the target fall asleep, weakest first. +2d8 per higher slot.",
		"short_name": "Sleep",
	},
	"SCORCHING_RAY": {
		"save_ability": null,
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Scorching Ray",
		"level": 2,
		"target": "ENEMY",
		"range": 10,
		"dice": "2d6",
		"damage_type": "fire",
		"description": "3 rays, each a spell attack for 2d6 fire. +1 ray per higher slot.",
		"short_name": "Scorch Ray",
	},
	"SHATTER": {
		"save_ability": "CON",
		"upcast_dice": 1,
		"radius": 1,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Shatter",
		"level": 2,
		"target": "ENEMY_AREA",
		"range": 10,
		"dice": "3d8",
		"damage_type": "thunder",
		"description": "Foes around the target: CON save or 3d8 thunder, half on a save. +1d8 per higher slot.",
		"short_name": "Shatter",
	},
	"FIREBALL": {
		"save_ability": "DEX",
		"upcast_dice": 1,
		"radius": 2,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Fireball",
		"level": 3,
		"target": "ENEMY_AREA",
		"range": 12,
		"dice": "8d6",
		"damage_type": "fire",
		"description": "Foes within 10 ft of the target: DEX save or 8d6 fire, half on a save. +1d6 per higher slot.",
		"short_name": "Fireball",
	},
	"ACID_ARROW": {
		"save_ability": null,
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Acid Arrow",
		"level": 2,
		"target": "ENEMY",
		"range": 12,
		"dice": "6d4",
		"damage_type": "acid",
		"description": "Ranged spell attack: 6d4 acid. +1d4 per higher slot.",
		"short_name": "Acid Arrow",
	},
	"LIGHTNING_BOLT": {
		"save_ability": "DEX",
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Lightning Bolt",
		"level": 3,
		"target": "ENEMY_AREA",
		"range": 12,
		"dice": "8d6",
		"damage_type": "lightning",
		"description": "A bolt through every foe in a line toward the target: DEX save or 8d6 lightning, half on a save. +1d6 per higher slot.",
		"short_name": "Light. Bolt",
	},
	"ICE_STORM": {
		"save_ability": "DEX",
		"upcast_dice": 1,
		"radius": 2,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Ice Storm",
		"level": 4,
		"target": "ENEMY_AREA",
		"range": 12,
		"dice": "4d10",
		"damage_type": "cold",
		"description": "Hail crashes down within 10 ft of the target: DEX save or 4d10 cold, half on a save. +1d10 per higher slot.",
		"short_name": "Ice Storm",
	},
	"CONE_OF_COLD": {
		"save_ability": "CON",
		"upcast_dice": 1,
		"radius": 2,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Cone of Cold",
		"level": 5,
		"target": "ENEMY_AREA",
		"range": 6,
		"dice": "8d8",
		"damage_type": "cold",
		"description": "A blast of frost around the target: CON save or 8d8 cold, half on a save. +1d8 per higher slot.",
		"short_name": "Cone Cold",
	},
	"CALL_LIGHTNING": {
		"save_ability": "DEX",
		"upcast_dice": 1,
		"radius": 1,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Call Lightning",
		"level": 3,
		"target": "ENEMY_AREA",
		"range": 12,
		"dice": "3d10",
		"damage_type": "lightning",
		"description": "A bolt strikes the target: foes within 5 ft make a DEX save or take 3d10 lightning, half on a save. +1d10 per higher slot.",
		"short_name": "Call Light.",
	},
	"SACRED_FLAME": {
		"save_ability": "DEX",
		"upcast_dice": 0,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Sacred Flame",
		"level": 0,
		"target": "ENEMY",
		"range": 8,
		"dice": "1d8",
		"damage_type": "radiant",
		"description": "DEX save or 1d8 radiant.",
		"short_name": "Sacred Fl.",
	},
	"TOLL_THE_DEAD": {
		"save_ability": "WIS",
		"upcast_dice": 0,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Toll the Dead",
		"level": 0,
		"target": "ENEMY",
		"range": 8,
		"dice": "1d8",
		"damage_type": "necrotic",
		"description": "WIS save or 1d8 necrotic (1d12 if it's already hurt).",
		"short_name": "Toll Dead",
	},
	"INFLICT_WOUNDS": {
		"save_ability": null,
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Inflict Wounds",
		"level": 1,
		"target": "ENEMY",
		"range": 1,
		"dice": "3d10",
		"damage_type": "necrotic",
		"description": "Melee spell attack: 3d10 necrotic. +1d10 per higher slot.",
		"short_name": "Inflict",
	},
	"SPIRIT_GUARDIANS": {
		"save_ability": "WIS",
		"upcast_dice": 1,
		"radius": 2,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Spirit Guardians",
		"level": 3,
		"target": "ADJACENT_AREA",
		"range": 2,
		"dice": "3d8",
		"damage_type": "radiant",
		"description": "Spirits strike foes within 10 ft: WIS save or 3d8 radiant, half on a save. +1d8 per higher slot.",
		"short_name": "Guardians",
	},
	"FLAME_STRIKE": {
		"save_ability": "DEX",
		"upcast_dice": 1,
		"radius": 1,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Flame Strike",
		"level": 5,
		"target": "ENEMY_AREA",
		"range": 12,
		"dice": "8d6",
		"damage_type": "fire",
		"description": "A pillar of holy fire around the target: DEX save or 8d6 fire and radiant, half on a save. +1d6 per higher slot.",
		"short_name": "Flame Strk",
	},
	"MASS_CURE_WOUNDS": {
		"save_ability": null,
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Mass Cure Wounds",
		"level": 5,
		"target": "PARTY",
		"range": 6,
		"dice": "3d8",
		"damage_type": "healing",
		"description": "Allies within 30 ft heal 3d8 + WIS. +1d8 per higher slot.",
		"short_name": "Mass Cure",
	},
	"GUIDING_BOLT": {
		"save_ability": null,
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Guiding Bolt",
		"level": 1,
		"target": "ENEMY",
		"range": 10,
		"dice": "4d6",
		"damage_type": "radiant",
		"description": "Ranged spell attack: 4d6 radiant; the next attack on the target has advantage. +1d6 per higher slot.",
		"short_name": "Guide Bolt",
	},
	"CURE_WOUNDS": {
		"save_ability": null,
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Cure Wounds",
		"level": 1,
		"target": "ALLY",
		"range": 1,
		"dice": "1d8",
		"damage_type": "healing",
		"description": "Touch: heal 1d8 + WIS. +1d8 per higher slot.",
		"short_name": "Cure",
	},
	"HEALING_WORD": {
		"save_ability": null,
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": true,
		"out_of_combat_only": false,
		"display_name": "Healing Word",
		"level": 1,
		"target": "ALLY",
		"range": 6,
		"dice": "1d4",
		"damage_type": "healing",
		"description": "Bonus action, 30 ft: heal 1d4 + WIS. +1d4 per higher slot.",
		"short_name": "Heal Word",
	},
	"BLESS": {
		"save_ability": null,
		"upcast_dice": 0,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": false,
		"display_name": "Bless",
		"level": 1,
		"target": "PARTY",
		"range": 6,
		"dice": "1d4",
		"damage_type": "bless",
		"description": "Allies within 30 ft add 1d4 to attack rolls until the fight ends.",
		"short_name": "Bless",
	},
	"PRAYER_OF_HEALING": {
		"save_ability": null,
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": true,
		"display_name": "Prayer of Healing",
		"level": 2,
		"target": "PARTY",
		"range": 6,
		"dice": "2d8",
		"damage_type": "healing",
		"description": "Out of combat: every hero heals 2d8 + WIS. +1d8 per higher slot.",
		"short_name": "Prayer",
	},
	"MASS_HEALING_WORD": {
		"save_ability": null,
		"upcast_dice": 1,
		"radius": 0,
		"bonus_action": true,
		"out_of_combat_only": false,
		"display_name": "Mass Healing Word",
		"level": 3,
		"target": "PARTY",
		"range": 6,
		"dice": "1d4",
		"damage_type": "healing",
		"description": "Bonus action: allies within 30 ft heal 1d4 + WIS. +1d4 per higher slot.",
		"short_name": "Mass Heal",
	},
	"REVIVIFY": {
		"save_ability": null,
		"upcast_dice": 0,
		"radius": 0,
		"bonus_action": false,
		"out_of_combat_only": true,
		"display_name": "Revivify",
		"level": 3,
		"target": "DEAD_ALLY",
		"range": 0,
		"dice": "1d1",
		"damage_type": "life",
		"description": "Out of combat, costs 300 gold: a fallen hero returns with 1 HP.",
		"short_name": "Revivify",
	},
}

const MONSTERS := {
	"GIANT_RAT": {
		"display_name": "Giant Rat",
		"challenge": "1/8",
		"cr": 0.125,
		"xp": 25,
		"armor_class": 12,
		"hit_dice": "2d6",
		"speed": 6,
		"scores": [7, 15, 11, 2, 10, 4],
		"attacks": [{
			"name": "Bite",
			"to_hit": 4,
			"damage": "1d4+2",
			"damage_type": "piercing",
			"range": 1,
		}],
		"passive_perception": 10,
	},
	"KOBOLD": {
		"display_name": "Kobold",
		"challenge": "1/8",
		"cr": 0.125,
		"xp": 25,
		"armor_class": 12,
		"hit_dice": "2d6-2",
		"speed": 6,
		"scores": [7, 15, 9, 8, 7, 8],
		"attacks": [{
			"name": "Dagger",
			"to_hit": 4,
			"damage": "1d4+2",
			"damage_type": "piercing",
			"range": 1,
		}, {
			"name": "Sling",
			"to_hit": 4,
			"damage": "1d4+2",
			"damage_type": "bludgeoning",
			"range": 6,
		}],
		"passive_perception": 8,
	},
	"GOBLIN": {
		"display_name": "Goblin",
		"challenge": "1/4",
		"cr": 0.25,
		"xp": 50,
		"armor_class": 15,
		"hit_dice": "2d6",
		"speed": 6,
		"scores": [8, 14, 10, 10, 8, 8],
		"attacks": [{
			"name": "Scimitar",
			"to_hit": 4,
			"damage": "1d6+2",
			"damage_type": "slashing",
			"range": 1,
		}, {
			"name": "Shortbow",
			"to_hit": 4,
			"damage": "1d6+2",
			"damage_type": "piercing",
			"range": 8,
		}],
		"passive_perception": 9,
	},
	"SKELETON": {
		"display_name": "Skeleton",
		"challenge": "1/4",
		"cr": 0.25,
		"xp": 50,
		"armor_class": 13,
		"hit_dice": "2d8+4",
		"speed": 6,
		"scores": [10, 14, 15, 6, 8, 5],
		"attacks": [{
			"name": "Shortsword",
			"to_hit": 4,
			"damage": "1d6+2",
			"damage_type": "piercing",
			"range": 1,
		}, {
			"name": "Shortbow",
			"to_hit": 4,
			"damage": "1d6+2",
			"damage_type": "piercing",
			"range": 8,
		}],
		"passive_perception": 9,
	},
	"ZOMBIE": {
		"display_name": "Zombie",
		"challenge": "1/4",
		"cr": 0.25,
		"xp": 50,
		"armor_class": 8,
		"hit_dice": "3d8+9",
		"speed": 4,
		"scores": [13, 6, 16, 3, 6, 5],
		"attacks": [{
			"name": "Slam",
			"to_hit": 3,
			"damage": "1d6+1",
			"damage_type": "bludgeoning",
			"range": 1,
		}],
		"passive_perception": 8,
	},
	"ORC": {
		"display_name": "Orc",
		"challenge": "1/2",
		"cr": 0.5,
		"xp": 100,
		"armor_class": 13,
		"hit_dice": "2d8+6",
		"speed": 6,
		"scores": [16, 12, 16, 7, 11, 10],
		"attacks": [{
			"name": "Greataxe",
			"to_hit": 5,
			"damage": "1d12+3",
			"damage_type": "slashing",
			"range": 1,
		}, {
			"name": "Javelin",
			"to_hit": 5,
			"damage": "1d6+3",
			"damage_type": "piercing",
			"range": 6,
		}],
		"passive_perception": 10,
	},
	"GNOLL": {
		"display_name": "Gnoll",
		"challenge": "1/2",
		"cr": 0.5,
		"xp": 100,
		"armor_class": 15,
		"hit_dice": "5d8",
		"speed": 6,
		"scores": [14, 12, 11, 6, 10, 7],
		"attacks": [{
			"name": "Spear",
			"to_hit": 4,
			"damage": "1d6+2",
			"damage_type": "piercing",
			"range": 1,
		}, {
			"name": "Longbow",
			"to_hit": 3,
			"damage": "1d8+1",
			"damage_type": "piercing",
			"range": 12,
		}],
		"passive_perception": 10,
	},
	"GHOUL": {
		"display_name": "Ghoul",
		"challenge": "1",
		"cr": 1.0,
		"xp": 200,
		"armor_class": 12,
		"hit_dice": "5d8",
		"speed": 6,
		"scores": [13, 15, 10, 7, 10, 6],
		"attacks": [{
			"name": "Claws",
			"to_hit": 4,
			"damage": "2d4+2",
			"damage_type": "slashing",
			"range": 1,
		}],
		"passive_perception": 10,
	},
	"BUGBEAR": {
		"display_name": "Bugbear",
		"challenge": "1",
		"cr": 1.0,
		"xp": 200,
		"armor_class": 16,
		"hit_dice": "5d8+5",
		"speed": 6,
		"scores": [15, 14, 13, 8, 11, 9],
		"attacks": [{
			"name": "Morningstar",
			"to_hit": 4,
			"damage": "2d8+2",
			"damage_type": "piercing",
			"range": 1,
		}],
		"passive_perception": 10,
	},
	"OGRE": {
		"display_name": "Ogre",
		"challenge": "2",
		"cr": 2.0,
		"xp": 450,
		"armor_class": 11,
		"hit_dice": "7d10+21",
		"speed": 8,
		"scores": [19, 8, 16, 5, 7, 7],
		"attacks": [{
			"name": "Greatclub",
			"to_hit": 6,
			"damage": "2d8+4",
			"damage_type": "bludgeoning",
			"range": 1,
		}, {
			"name": "Javelin",
			"to_hit": 6,
			"damage": "2d6+4",
			"damage_type": "piercing",
			"range": 6,
		}],
		"passive_perception": 8,
	},
}

const SUBCLASSES := {
	"PALADIN": {
		"char_class": "FIGHTER",
		"display_name": "Paladin",
		"lore": "Bound by a sacred oath, a paladin fights as the sword of the gods: divine light burns in their strikes and their touch mends the wounded.",
	},
	"KNIGHT": {
		"char_class": "FIGHTER",
		"display_name": "Knight",
		"lore": "Sworn protectors who hold the line. A knight calls out the deadliest foe, shields their companions and rallies them when the battle turns grim.",
	},
	"ASSASSIN": {
		"char_class": "ROGUE",
		"display_name": "Assassin",
		"lore": "Killers who strike before the enemy knows the fight has begun, with poisoned steel and a perfect sense for the moment of weakness.",
	},
	"RANGER": {
		"char_class": "ROGUE",
		"display_name": "Ranger",
		"lore": "Scouts and hunters of the wild places beneath the world. A ranger marks their prey and looses arrows with uncanny precision.",
	},
	"NECROMANCER": {
		"char_class": "WIZARD",
		"display_name": "Necromancer",
		"lore": "Students of the boundary between life and death. Necromancers draw vitality from the slain and raise the fallen to fight at their side.",
	},
	"MAGE": {
		"char_class": "WIZARD",
		"display_name": "Mage",
		"lore": "Masters of raw arcane power. A mage wrings more force out of every spell and can pour their very essence into a single devastating casting.",
	},
	"PRIEST": {
		"char_class": "CLERIC",
		"display_name": "Priest",
		"lore": "Servants of the gods of life and mercy. A priest's healing flows stronger than any other's, and their faith can pull allies back from death's door.",
	},
	"TEMPEST": {
		"char_class": "CLERIC",
		"display_name": "Tempest",
		"lore": "Chosen of the storm gods. Thunder answers their prayers, and lightning lashes out at anyone foolish enough to strike them.",
	},
}

const FEATURES := {
	"DIVINE_SMITE": {
		"subclass": "PALADIN",
		"level": 3,
		"title": "Divine Smite",
		"text": "Ready a smite: your next melee hit deals +2d8 radiant (+1d8 vs undead). 2 per long rest (3 at 7th).",
	},
	"LAY_ON_HANDS": {
		"subclass": "PALADIN",
		"level": 3,
		"title": "Lay on Hands",
		"text": "A pool of 5 x level HP. Action: touch an ally to heal them from the pool. Refills on a long rest.",
	},
	"AURA_OF_PROTECTION": {
		"subclass": "PALADIN",
		"level": 7,
		"title": "Aura of Protection",
		"text": "Allies within 10 ft (and you) gain +1 AC, and add your CHA modifier (min +1) to death saves.",
	},
	"SHIELD_WALL": {
		"subclass": "KNIGHT",
		"level": 3,
		"title": "Shield Wall",
		"text": "Trained in heavy defence: +1 AC.",
	},
	"CHALLENGE": {
		"subclass": "KNIGHT",
		"level": 3,
		"title": "Knightly Challenge",
		"text": "Bonus action: challenge a foe. It has disadvantage attacking anyone but you, and turns its rage on you.",
	},
	"RALLYING_CRY": {
		"subclass": "KNIGHT",
		"level": 3,
		"title": "Rallying Cry",
		"text": "When you use Second Wind, allies within 30 ft regain HP equal to your level.",
	},
	"WARDING_MANEUVER": {
		"subclass": "KNIGHT",
		"level": 7,
		"title": "Warding Maneuver",
		"text": "Reaction: when an adjacent ally is hit, add 1d8 to their AC, possibly turning the hit into a miss.",
	},
	"ASSASSINATE": {
		"subclass": "ASSASSIN",
		"level": 3,
		"title": "Assassinate",
		"text": "Advantage on attacks against foes that haven't acted yet this fight; hits on surprised foes are critical.",
	},
	"ENVENOM": {
		"subclass": "ASSASSIN",
		"level": 3,
		"title": "Envenom",
		"text": "Bonus action: coat your blade. Your next hit deals +2d6 poison. 2 per short rest.",
	},
	"DEATH_STRIKE": {
		"subclass": "ASSASSIN",
		"level": 7,
		"title": "Death Strike",
		"text": "When you hit a foe that hasn't acted yet, it makes a CON save or takes double damage.",
	},
	"ARCHERY": {
		"subclass": "RANGER",
		"level": 3,
		"title": "Ranger's Longbow",
		"text": "You wield a longbow (1d8, 60 ft) and gain +2 to ranged attack rolls.",
	},
	"HUNTERS_MARK": {
		"subclass": "RANGER",
		"level": 3,
		"title": "Hunter's Mark",
		"text": "Bonus action: mark a foe. Your weapon hits against it deal +1d6 damage.",
	},
	"VOLLEY": {
		"subclass": "RANGER",
		"level": 7,
		"title": "Volley",
		"text": "Action: loose an arrow at every foe within 5 ft of a target you can see.",
	},
	"GRIM_HARVEST": {
		"subclass": "NECROMANCER",
		"level": 3,
		"title": "Grim Harvest",
		"text": "When one of your spells of 1st level or higher kills a foe, you regain 2 x the spell's level in HP.",
	},
	"RAISE_DEAD": {
		"subclass": "NECROMANCER",
		"level": 3,
		"title": "Raise Dead",
		"text": "Action + a spell slot: raise a corpse within 30 ft as a skeleton that fights for you. One at a time.",
	},
	"UNDEAD_THRALLS": {
		"subclass": "NECROMANCER",
		"level": 7,
		"title": "Undead Thralls",
		"text": "Control two skeletons; they gain HP equal to your level and add your proficiency to damage.",
	},
	"ARCANE_RECOVERY": {
		"subclass": "MAGE",
		"level": 3,
		"title": "Arcane Recovery",
		"text": "Once per long rest, a short rest also restores spell slots totalling half your level (rounded up).",
	},
	"ARCANE_POTENCY": {
		"subclass": "MAGE",
		"level": 3,
		"title": "Arcane Potency",
		"text": "Add your INT modifier to the damage of your damaging spells.",
	},
	"OVERCHANNEL": {
		"subclass": "MAGE",
		"level": 7,
		"title": "Overchannel",
		"text": "Once per long rest: your next leveled damage spell deals maximum damage.",
	},
	"DISCIPLE_OF_LIFE": {
		"subclass": "PRIEST",
		"level": 3,
		"title": "Disciple of Life",
		"text": "Healing spells restore an extra 2 + the spell's level. You always know Bless, and Revivify at 5th.",
	},
	"PRESERVE_LIFE": {
		"subclass": "PRIEST",
		"level": 3,
		"title": "Preserve Life",
		"text": "Action: share 5 x level HP among wounded allies within 30 ft (each up to half their max). 1 per short rest.",
	},
	"BLESSED_HEALER": {
		"subclass": "PRIEST",
		"level": 7,
		"title": "Blessed Healer",
		"text": "When you heal another creature with a spell, you regain 2 + the spell's level.",
	},
	"WRATH_OF_THE_STORM": {
		"subclass": "TEMPEST",
		"level": 3,
		"title": "Wrath of the Storm",
		"text": "Reaction: when a foe hits you in melee, it makes a DEX save or takes 2d8 lightning (half on a save). WIS times per long rest.",
	},
	"DESTRUCTIVE_WRATH": {
		"subclass": "TEMPEST",
		"level": 3,
		"title": "Destructive Wrath",
		"text": "Ready it: your next lightning or thunder damage roll is maximised. 1 per short rest. You know Thunderwave, and Call Lightning at 5th.",
	},
	"DIVINE_STRIKE": {
		"subclass": "TEMPEST",
		"level": 7,
		"title": "Divine Strike",
		"text": "Once per turn, your weapon hits deal +1d8 thunder.",
	},
}

const ITEM_TYPES := {
	"MAGIC_WEAPON": {
		"caster_only": false,
		"fixed_rarity": null,
		"display_name": "Weapon",
		"slot": "WEAPON",
		"description": "+N to attack and damage rolls with your weapons.",
	},
	"MAGIC_ARMOR": {
		"caster_only": false,
		"fixed_rarity": null,
		"display_name": "Armor",
		"slot": "ARMOR",
		"description": "+N to Armor Class.",
	},
	"WAND_OF_THE_WAR_MAGE": {
		"caster_only": true,
		"fixed_rarity": null,
		"display_name": "Wand of the War Mage",
		"slot": "FOCUS",
		"description": "+N to spell attack rolls and spell save DC.",
	},
	"RING_OF_PROTECTION": {
		"caster_only": false,
		"fixed_rarity": "UNCOMMON",
		"display_name": "Ring of Protection",
		"slot": "RING",
		"description": "+1 to Armor Class and saving throws.",
	},
	"CLOAK_OF_PROTECTION": {
		"caster_only": false,
		"fixed_rarity": "UNCOMMON",
		"display_name": "Cloak of Protection",
		"slot": "CLOAK",
		"description": "+1 to Armor Class and saving throws.",
	},
	"AMULET_OF_HEALTH": {
		"caster_only": false,
		"fixed_rarity": "RARE",
		"display_name": "Amulet of Health",
		"slot": "NECK",
		"description": "Your Constitution score is 19.",
	},
	"PERIAPT_OF_WOUND_CLOSURE": {
		"caster_only": false,
		"fixed_rarity": "UNCOMMON",
		"display_name": "Periapt of Wound Closure",
		"slot": "NECK",
		"description": "You stabilise automatically at 0 HP, and hit dice heal double.",
	},
	"GAUNTLETS_OF_OGRE_POWER": {
		"caster_only": false,
		"fixed_rarity": "UNCOMMON",
		"display_name": "Gauntlets of Ogre Power",
		"slot": "HANDS",
		"description": "Your Strength score is 19.",
	},
	"HEADBAND_OF_INTELLECT": {
		"caster_only": false,
		"fixed_rarity": "UNCOMMON",
		"display_name": "Headband of Intellect",
		"slot": "HEAD",
		"description": "Your Intelligence score is 19.",
	},
	"BOOTS_OF_STRIDING": {
		"caster_only": false,
		"fixed_rarity": "UNCOMMON",
		"display_name": "Boots of Striding",
		"slot": "FEET",
		"description": "+5 ft speed (one extra square of movement).",
	},
}
