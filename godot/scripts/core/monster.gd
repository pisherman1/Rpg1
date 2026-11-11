class_name Monster
extends Creature
## A monster built from a stat block in Rules.MONSTERS.

var type := ""
var base_max_hp := 1
## Extra hit points granted after creation (Undead Thralls).
var extra_hp := 0
## Has noticed the party; aware monsters hunt the party even outside combat.
var aware := false
## Surprised creatures lose their first turn of combat and can't react until it ends.
var surprised := false
var reaction_available := true
## Sleep spell: loses its turns until damaged; attacks against it have advantage.
var asleep := false
## Fights for the party (a necromancer's skeleton), raised by [member master].
var ally := false
var master: Hero = null
## Has taken a turn in the current fight (for Assassinate).
var acted := false
## Knight who challenged this monster, and ranger who marked it.
var challenger: Hero = null
var hunted_by: Hero = null
## Extra damage on its attacks (Undead Thralls).
var bonus_damage := 0


## [param max_hp] fixes the hit points (for loading); otherwise they're rolled from the hit dice.
static func create(monster_type: String, dice: Dice, max_hp: int = -1) -> Monster:
	var m := Monster.new()
	m.type = monster_type
	m.name = Rules.MONSTERS[monster_type]["display_name"]
	m.base_max_hp = max_hp if max_hp > 0 else maxi(1, dice.roll_expr(Rules.MONSTERS[monster_type]["hit_dice"]))
	m.hp = m.base_max_hp
	return m


func stats() -> Dictionary:
	return Rules.MONSTERS[type]


func get_max_hp() -> int:
	return base_max_hp + extra_hp


func get_ac() -> int:
	return stats()["armor_class"]


func get_speed() -> int:
	return stats()["speed"]


func ability_mod(ability: String) -> int:
	return Rules.ability_mod(stats()["scores"][Rules.ABILITIES.find(ability)])


func is_dead() -> bool:
	return hp <= 0


func melee() -> Dictionary:
	return Rules.monster_melee(type)


func ranged_attack() -> Dictionary:
	return Rules.monster_ranged(type)
