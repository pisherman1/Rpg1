extends "res://tests/test_base.gd"

const ScriptedDice := preload("res://tests/scripted_dice.gd")


func test_ability_modifiers_follow_the_table() -> void:
	for pair in [[1, -5], [8, -1], [9, -1], [10, 0], [11, 0], [16, 3], [20, 5]]:
		check_eq(Rules.ability_mod(pair[0]), pair[1], "score %d" % pair[0])


func test_dice_expressions_parse_and_print() -> void:
	check_eq(Dice.parse("2d6+3"), {"count": 2, "sides": 6, "bonus": 3})
	check_eq(Dice.parse("2d6-2")["bonus"], -2)
	check_eq(Dice.expr(1, 8), "1d8")
	check_eq(Dice.with_count("7d10+21", 7), "7d10+21")
	check_eq(Dice.average("7d10+21"), 59.5)


func test_rolls_stay_in_range() -> void:
	var dice := Dice.new(1)
	for i in 2000:
		var r := dice.roll_expr("2d6+3")
		check(r >= 5 and r <= 15, "2d6+3 rolled %d" % r)
		var a := dice.ability_score()
		check(a >= 3 and a <= 18)
		var n: int = dice.d20(Dice.Mode.ADVANTAGE)["natural"]
		check(n >= 1 and n <= 20)


func test_critical_hits_double_dice_but_not_bonus() -> void:
	check_eq(ScriptedDice.new([6, 6, 6, 6]).roll_expr("2d6+3", true), 4 * 6 + 3)


func test_advantage_takes_higher_disadvantage_lower() -> void:
	check_eq(ScriptedDice.new([4, 17]).d20(Dice.Mode.ADVANTAGE)["natural"], 17)
	check_eq(ScriptedDice.new([4, 17]).d20(Dice.Mode.DISADVANTAGE)["natural"], 4)
	check_eq(Dice.mode_of(true, true), Dice.Mode.NORMAL)


func test_lucky_rerolls_natural_ones() -> void:
	check_eq(ScriptedDice.new([1, 15]).d20(Dice.Mode.NORMAL, true)["natural"], 15)
	check_eq(ScriptedDice.new([1, 15]).d20(Dice.Mode.NORMAL, false)["natural"], 1)


func test_four_d6_drops_lowest() -> void:
	check_eq(ScriptedDice.new([1, 5, 5, 5]).ability_score(), 15)


func test_hero_creation_assigns_by_class_priority_and_adds_race() -> void:
	var hero := Hero.create("Bruenor", "DWARF", "FIGHTER", [8, 15, 10, 14, 12, 13])
	check_eq(hero.score("STR"), 15)
	check_eq(hero.score("CON"), 16)
	check_eq(hero.score("DEX"), 13)
	check_eq(hero.get_max_hp(), 14)
	check_eq(hero.get_ac(), 18)
	check_eq(hero.proficiency(), 2)
	check_eq(hero.attack_bonus(Rules.WEAPONS["LONGSWORD"]), 4)


func test_rogue_uses_dex_for_finesse_and_light_armor() -> void:
	var hero := Hero.create("Vex", "HALFLING", "ROGUE", [15, 14, 13, 12, 10, 8])
	check_eq(hero.score("DEX"), 17)
	check_eq(hero.ability_for(Rules.WEAPONS["SHORTSWORD"]), "DEX")
	check_eq(hero.get_ac(), 14)
	check_eq(hero.save_bonus("DEX"), 5)
	check_eq(hero.save_bonus("WIS"), 1)


func test_levelling_raises_hp_proficiency_and_slots() -> void:
	var hero := Hero.create("Elminster", "HUMAN", "WIZARD", [16, 14, 13, 12, 10, 8])
	check_eq(hero.spell_slots, [2])
	var start_hp := hero.get_max_hp()
	var msgs := hero.gain_xp(Rules.XP_TABLE[3])
	check_eq(msgs.size(), 3)
	check_eq(hero.level, 4)
	check_eq(hero.spell_slots, [4, 3])
	check_eq(hero.get_max_hp(), start_hp + 3 * (4 + 2))
	hero.gain_xp(Rules.XP_TABLE[4] - hero.xp)
	check_eq(hero.proficiency(), 3)
	check_eq(hero.cantrip_dice(), 2)


func test_spent_slots_stay_spent_on_level_up() -> void:
	var hero := Hero.create("Cleo", "HUMAN", "CLERIC", [16, 14, 13, 12, 10, 8])
	hero.spend_slot(1)
	hero.spend_slot(1)
	hero.gain_xp(Rules.XP_TABLE[1])
	check_eq(hero.spell_slots, [1])
	hero.long_rest()
	check_eq(hero.spell_slots, [3])


func test_healing_from_zero_clears_death_saves() -> void:
	var hero := Hero.create("Pip", "HALFLING", "ROGUE", [15, 14, 13, 12, 10, 8])
	hero.hp = 0
	hero.death_failures = 2
	check(hero.is_unconscious())
	check_eq(hero.heal(5), 5)
	check_eq(hero.death_failures, 0)


func test_monster_stats_match_their_blocks() -> void:
	var ogre := Monster.create("OGRE", ScriptedDice.new([10, 10, 10, 10, 10, 10, 10]))
	check_eq(ogre.get_max_hp(), 91)
	check_eq(ogre.save_bonus("DEX"), -1)
	check_eq(Rules.monster_melee("GOBLIN")["name"], "Scimitar")
	check_eq(Rules.monster_ranged("GOBLIN")["range"], 8)
	check_eq(Monster.create("KOBOLD", ScriptedDice.new([1, 1])).get_max_hp(), 1)


func test_therian_bonuses_are_in_line_with_other_races() -> void:
	var t := Hero.create("Kit", "THERIAN", "ROGUE", [15, 14, 13, 12, 10, 8])
	check_eq(t.score("DEX"), 17)
	check_eq(t.score("WIS"), 14)
	check_eq(t.get_speed(), 6)
	check_eq(Rules.RACES["THERIAN"]["sight"], 9)
	var sum := func(r): return Rules.RACES[r]["bonuses"].values().reduce(func(a, b): return a + b, 0)
	check_eq(sum.call("THERIAN"), 3)
	check_eq(sum.call("ELF"), sum.call("THERIAN"))


func test_party_builder_builds_four_heroes() -> void:
	var b := Party.Builder.new(Dice.new(1))
	b.drafts[0].race = "THERIAN"
	var heroes := b.build()
	check_eq(heroes.size(), 4)
	check_eq(heroes[0].race, "THERIAN")
	b.apply_party(Party.PARTIES[0])
	var classic := b.build()
	check_eq(classic.map(func(h): return h.name), ["Brakka", "Pip", "Ilyra", "Aldric"])
	check_eq(classic[0].gender, "FEMALE")


func test_toggle_gender_swaps_default_names() -> void:
	var b := Party.Builder.new(Dice.new(2))
	var was_male: bool = b.drafts[0].gender == "MALE"
	b.toggle_gender(0)
	check_eq(b.drafts[0].gender, "FEMALE" if was_male else "MALE")
	check(Party.names_for(b.drafts[0].gender).has(b.drafts[0].name))
	b.drafts[0].name = "Custom"
	b.toggle_gender(0)
	check_eq(b.drafts[0].name, "Custom")


func test_items_describe_themselves() -> void:
	var hero := Hero.create("Vex", "HUMAN", "FIGHTER", [15, 14, 13, 12, 10, 8])
	var sword := Items.make("MAGIC_WEAPON", 2)
	check_eq(Items.rarity(sword), "RARE")
	check_eq(Items.name_for(sword, hero), "+2 Longsword")
	check_eq(hero.equip(sword), null)
	check_eq(hero.weapon_bonus(), 2)
	var dice := Dice.new(3)
	for i in 200:
		var it := Items.random_item(dice, 1 + i % 8, i % 5 == 0)
		check(Rules.ITEM_TYPES.has(it["type"]))
