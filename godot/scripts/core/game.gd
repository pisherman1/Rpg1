class_name Game
extends RefCounted
## The whole game state for a party of heroes. The UI calls [method tap], [method select_hero] and
## [method perform] for input and [method tick] on a timer; everything else is read-only state for rendering.
##
## Log entries are {"text", "kind"} with kind INFO/ROLL/GOOD/BAD/LOOT/LEVEL.
## Effects are {"type": "text", "x", "y", "text", "kind"} or {"type": "bolt", "from_x", "from_y", "to_x", "to_y", "kind"}
## with kind DAMAGE/HEAL/MISS/CRIT/FIRE/RADIANT/FORCE/ARROW/INFO/LOOT/LIGHTNING/NECROTIC.
## Actions are {"id", "label", "enabled", "active", "spell", "group"}.

enum Phase {EXPLORING, PLAYER_TURN, MONSTER_TURN, GAME_OVER}

const MOVES := "Moves"
const MAX_LOG := 60
const SAVE_VERSION := 2

var party: Array = []
var dice: Dice
var depth := 1
var dungeon: Dungeon
var monsters: Array = []
var corpses: Array[Vector2i] = []
## Where fallen heroes lie on the current level.
var graves: Array[Vector2i] = []
var log_entries: Array = []
var _pending_fx: Array = []

var phase: int = Phase.EXPLORING
## Index into [member party] of the hero leading the march outside combat.
var leader_index := 0
var _trail: Array[Vector2i] = []

# --- Combat state ---------------------------------------------------------------
var turn_order: Array = []
var _initiative_rolls := {}
var turn_index := 0
var round_number := 0

var movement_left := 0
var action_available := false
var bonus_action_available := false
var _sneak_attack_used := false
var _aiming := false
var _disengaged := false
var _moved_this_turn := false
var _auto_end_turn := false
## Extra Attack: weapon attacks left from the current Attack action.
var attacks_left := 0
## The creature whose turn it was left the order; turn_index already points at the next one.
var _turn_holder_removed := false

var _divine_strike_used := false
var _monster_steps_left := 0
var _monster_done := false

# --- Targeting & movement -----------------------------------------------------------
var armed_spell := ""
var armed_ranged := false
var armed_potion := false
## A subclass feature waiting for a target tap.
var armed_feature := ""
var _walk_path: Array[Vector2i] = []
var _walk_target: Monster = null
var _idle_ticks := 0

var long_rest_used := false
var kills := 0
var _stairs_hint_shown := false

var _level_ups: Array = []
var _pending_sounds: Array = []


func _init(heroes: Array, d: Dice = null, fresh: bool = true) -> void:
	assert(not heroes.is_empty())
	party = heroes
	dice = d if d != null else Dice.new()
	if fresh:
		_start_adventure()


func _start_adventure() -> void:
	var names := party.map(func(h): return "%s the %s %s" % [h.name, h.race_info()["display_name"], h.cls()["display_name"]])
	_log("The party enters the dungeon: " + ", ".join(names) + ".", "LEVEL")
	_enter_level()


# =================================================================================
# Party queries
# =================================================================================

func leader() -> Hero:
	return party[leader_index]


func living_heroes() -> Array:
	return party.filter(func(h): return not h.dead)


func conscious_heroes() -> Array:
	return party.filter(func(h): return not h.dead and h.hp > 0)


func current() -> Creature:
	return turn_order[turn_index] if turn_index >= 0 and turn_index < turn_order.size() else null


## The hero the action buttons apply to: whoever's turn it is, or the leader while exploring.
func active_hero() -> Hero:
	match phase:
		Phase.PLAYER_TURN:
			var c := current()
			return c as Hero if c is Hero else null
		Phase.EXPLORING:
			return leader()
	return null


func hero_at(x: int, y: int) -> Hero:
	for h in party:
		if not h.dead and h.x == x and h.y == y:
			return h
	return null


func monster_at(x: int, y: int) -> Monster:
	for m in monsters:
		if not m.is_dead() and m.x == x and m.y == y:
			return m
	return null


func is_occupied(x: int, y: int) -> bool:
	return monster_at(x, y) != null or hero_at(x, y) != null


func in_combat() -> bool:
	return phase == Phase.PLAYER_TURN or phase == Phase.MONSTER_TURN


## Hostile monsters in the current fight.
func _combat_monsters() -> Array:
	return turn_order.filter(func(c): return c is Monster and not c.is_dead() and not c.ally)


## The party's undead minions.
func minions() -> Array:
	return monsters.filter(func(m): return m.ally and not m.is_dead())


## A hostile monster on (x, y), if any.
func enemy_at(x: int, y: int) -> Monster:
	var m := monster_at(x, y)
	return m if m != null and not m.ally else null


func _on_stairs(h: Hero) -> bool:
	return h.x == dungeon.stairs.x and h.y == dungeon.stairs.y


func drain_fx() -> Array:
	var out := _pending_fx
	_pending_fx = []
	return out


## Level-up notices {"hero_index", "level", "hp_gain", "gains"}.
func drain_level_ups() -> Array:
	var out := _level_ups
	_level_ups = []
	return out


## Sound ids for events the renderer can't infer from visual effects.
func drain_sounds() -> Array:
	var out := _pending_sounds
	_pending_sounds = []
	return out


func _sound(id: String) -> void:
	_pending_sounds.append(id)


func is_busy() -> bool:
	return not _walk_path.is_empty() or phase == Phase.MONSTER_TURN or _auto_end_turn


func initiative_of(c: Creature):
	return _initiative_rolls.get(c)


# =================================================================================
# Level setup
# =================================================================================

func _enter_level() -> void:
	if not minions().is_empty():
		_log("Your skeletons crumble to dust as you descend.", "INFO")
	dungeon = Dungeon.generate(dice)
	monsters.clear()
	corpses.clear()
	graves.clear()
	turn_order.clear()
	_walk_path.clear()
	_walk_target = null
	phase = Phase.EXPLORING
	long_rest_used = false
	_stairs_hint_shown = false
	_ensure_living_leader()
	_place_party()
	_populate()
	_update_fov()
	_log("Depth %d. Find the stairs down." % depth, "INFO")


## Leader on the start square, everyone else on the nearest free squares.
func _place_party() -> void:
	var heroes := living_heroes()
	for h in heroes:
		h.move_to(-1, -1)
	leader().move_to(dungeon.start.x, dungeon.start.y)
	var spots := _squares_near(dungeon.start, heroes.size())
	var i := 0
	for h in heroes:
		if h == leader():
			continue
		var p: Vector2i = spots[i] if i < spots.size() else dungeon.start
		h.move_to(p.x, p.y)
		i += 1
	_reset_trail()


## Free walkable squares around [param origin] in breadth-first order (excluding origin).
func _squares_near(origin: Vector2i, count: int) -> Array[Vector2i]:
	var found: Array[Vector2i] = []
	var seen := {origin: true}
	var queue: Array[Vector2i] = [origin]
	var head := 0
	while head < queue.size() and found.size() < count:
		var p := queue[head]
		head += 1
		for d in Dungeon.DIRS:
			var n := p + d
			if seen.has(n) or not dungeon.is_walkable(n.x, n.y):
				continue
			seen[n] = true
			queue.append(n)
			if not is_occupied(n.x, n.y) and n != dungeon.stairs:
				found.append(n)
	return found


func _max_cr() -> float:
	if depth <= 1:
		return 0.25
	if depth == 2:
		return 0.5
	if depth <= 4:
		return 1.0
	return 2.0


func _min_cr() -> float:
	if depth >= 7:
		return 0.5
	if depth >= 4:
		return 0.25
	return 0.0


func _monster_pool() -> Array:
	var pool := []
	for t in Rules.MONSTERS:
		var cr: float = Rules.MONSTERS[t]["cr"]
		if cr <= _max_cr() and cr >= _min_cr() and t != "OGRE":
			pool.append(t)
	return pool if not pool.is_empty() else ["OGRE"]


## XP budget for one room's encounter: between an easy and a medium 5e encounter for the party.
func _encounter_budget() -> int:
	var easy := 0
	for h in living_heroes():
		easy += 25 * h.level
	return easy * (80 + dice.roll(80)) / 100


func _populate() -> void:
	var pool := _monster_pool()
	for ri in range(1, dungeon.rooms.size()):
		var room: Rect2i = dungeon.rooms[ri]
		if dice.chance(1, 2):
			_spawn_encounter(room, pool)
		var item_roll := dice.roll(20)
		var kind := ""
		if item_roll <= 7:
			kind = "CHEST"
		elif item_roll <= 10:
			kind = "POTION"
		elif item_roll <= 16:
			kind = "GOLD"
		if kind != "":
			var p = _free_square_in(room)
			if p != null:
				dungeon.items.append(Dungeon.make_item(p.x, p.y, kind))
	if depth % 4 == 0 or depth >= 9:
		for room in dungeon.rooms:
			if room.has_point(dungeon.stairs):
				_spawn_in(room, "OGRE")
				if depth >= 8:
					_spawn_in(room, "OGRE")
				break


## Fills [param room] with one monster type up to the encounter budget (at least one, at most six).
func _spawn_encounter(room: Rect2i, pool: Array) -> Array:
	var budget := _encounter_budget()
	var affordable := pool.filter(func(t): return Rules.MONSTERS[t]["xp"] <= budget)
	if affordable.is_empty():
		var cheapest: String = pool[0]
		for t in pool:
			if Rules.MONSTERS[t]["xp"] < Rules.MONSTERS[cheapest]["xp"]:
				cheapest = t
		affordable = [cheapest]
	var type: String = dice.pick(affordable)
	var count := clampi(budget / int(Rules.MONSTERS[type]["xp"]), 1, 6)
	var out := []
	for i in count:
		var m := _spawn_in(room, type)
		if m != null:
			out.append(m)
	return out


func _spawn_in(room: Rect2i, type: String) -> Monster:
	var p = _free_square_in(room)
	if p == null:
		return null
	var m := Monster.create(type, dice)
	m.move_to(p.x, p.y)
	monsters.append(m)
	return m


func _free_square_in(room: Rect2i):
	for i in 30:
		var x := room.position.x + dice.roll(room.size.x) - 1
		var y := room.position.y + dice.roll(room.size.y) - 1
		if dungeon.at(x, y) == Dungeon.Tile.FLOOR and not is_occupied(x, y) and dungeon.item_at(x, y) == null and \
				(x != dungeon.start.x or y != dungeon.start.y):
			return Vector2i(x, y)
	return null


# =================================================================================
# UI helpers
# =================================================================================

static func action(id: String, label: String, enabled: bool = true, active: bool = false, spell: String = "", group: String = "") -> Dictionary:
	return {"id": id, "label": label, "enabled": enabled, "active": active, "spell": spell, "group": group}


## One-line hint about what a tap will do right now.
func prompt() -> String:
	var h := active_hero()
	var target: String = Rules.SPELLS[armed_spell]["target"] if armed_spell != "" else ""
	if phase == Phase.GAME_OVER:
		return "The party has fallen. Tap New Party to try again."
	if target == "ALLY":
		return "Tap a hero (or portrait) for %s" % Rules.SPELLS[armed_spell]["display_name"]
	if target == "DEAD_ALLY":
		return "Tap a fallen hero's portrait to revive them"
	if armed_feature != "":
		return _feature_prompt(armed_feature)
	if armed_spell != "":
		return "Tap a target for %s" % Rules.SPELLS[armed_spell]["display_name"]
	if armed_potion:
		return "Tap a hero (or portrait) to give the potion"
	if armed_ranged:
		return "Tap a target for the %s" % (h.ranged_weapon().get("name", "") if h != null else "")
	if phase == Phase.MONSTER_TURN:
		return "%s's turn..." % (current().name if current() != null else "")
	if h != null and phase == Phase.PLAYER_TURN and h.is_unconscious():
		return "%s is dying! Death saves: %d successes, %d failures" % [h.name, h.death_successes, h.death_failures]
	if h != null and phase == Phase.PLAYER_TURN and attacks_left > 0:
		return "%s: Extra Attack! Tap a foe" % h.name
	if h != null and phase == Phase.PLAYER_TURN:
		return "%s's turn: tap to move or attack" % h.name
	if _on_stairs(leader()):
		return "Stairs! Tap Descend to go deeper"
	return "Tap to explore. Tap a portrait to change leader"


func _feature_prompt(f: String) -> String:
	match f:
		"LAY_ON_HANDS": return "Tap a hero (or portrait) to Lay on Hands"
		"CHALLENGE": return "Tap a foe within 30 ft to challenge"
		"HUNTERS_MARK": return "Tap a foe to mark as your quarry"
		"VOLLEY": return "Tap the target for your volley"
		"RAISE_DEAD": return "Tap a corpse within 30 ft to raise it"
	return "Tap a target"


## True while the active hero can still make a weapon attack this turn.
func can_attack() -> bool:
	return phase == Phase.PLAYER_TURN and (action_available or attacks_left > 0)


func _wounded() -> bool:
	return living_heroes().any(func(h): return h.hp < h.get_max_hp())


## Whether [param spell] could be cast by [param h] right now (ignoring targets).
func can_cast_now(h: Hero, spell: String) -> bool:
	if not h.can_cast(spell) or h.is_unconscious():
		return false
	var s: Dictionary = Rules.SPELLS[spell]
	var healing := Rules.spell_is_healing(spell)
	var timing := false
	match phase:
		Phase.EXPLORING:
			timing = s["out_of_combat_only"] or (healing and s["target"] != "ENEMY") or s["target"] == "DEAD_ALLY"
		Phase.PLAYER_TURN:
			timing = not s["out_of_combat_only"] and (bonus_action_available if s["bonus_action"] else action_available)
	if not timing:
		return false
	match s["target"]:
		"DEAD_ALLY":
			return party.any(func(x): return x.dead) and party_gold() >= Rules.REVIVIFY_COST
		"ALLY", "PARTY":
			return not healing or _wounded()
	return true


func available_actions() -> Array:
	var h := active_hero()
	if h == null:
		return []
	var actions := []
	var cls := h.char_class
	match phase:
		Phase.EXPLORING:
			if _on_stairs(h):
				actions.append(action("DESCEND", "Descend"))
			actions.append(action("POTION", "Potion x%d" % total_potions(), total_potions() > 0 and _wounded(), armed_potion))
			for spell in h.known_spells:
				var s: Dictionary = Rules.SPELLS[spell]
				if s["out_of_combat_only"] or (Rules.spell_is_healing(spell) and s["target"] != "ENEMY"):
					actions.append(action("SPELL", _spell_label(h, spell), can_cast_now(h, spell), armed_spell == spell, spell))
			actions.append_array(_feature_actions(h))
			var needs_rest := living_heroes().any(func(x): return x.hp < x.get_max_hp() or not x.second_wind_available or not x.action_surge_available)
			actions.append(action("SHORT_REST", "Short Rest", needs_rest))
			actions.append(action("LONG_REST", "Long Rest", not long_rest_used))
		Phase.PLAYER_TURN:
			var mine := not h.is_unconscious()
			var bonus_cunning := cls == "ROGUE" and h.level >= 2 and bonus_action_available
			actions.append(action("END_TURN", "End Turn", not _auto_end_turn))
			for spell in h.known_spells:
				if not Rules.SPELLS[spell]["out_of_combat_only"]:
					actions.append(action("SPELL", _spell_label(h, spell), mine and can_cast_now(h, spell), armed_spell == spell, spell))
			var rw := h.ranged_weapon()
			if not rw.is_empty():
				var parts: PackedStringArray = rw["name"].split(" ")
				actions.append(action("RANGED", parts[parts.size() - 1], mine and can_attack(), armed_ranged))
			if mine:
				actions.append_array(_feature_actions(h))
			if cls == "FIGHTER":
				actions.append(action("SECOND_WIND", "2nd Wind", mine and bonus_action_available and h.second_wind_available))
				if h.level >= 2:
					actions.append(action("ACTION_SURGE", "Surge", mine and not action_available and h.action_surge_available))
			if cls == "ROGUE":
				actions.append(action("AIM", "Aim", mine and bonus_action_available and not _moved_this_turn and not _aiming, _aiming))
			actions.append(action("DASH", "Dash (B)" if bonus_cunning else "Dash", mine and (action_available or bonus_cunning), false, "", MOVES))
			actions.append(action("DISENGAGE", "Disengage(B)" if bonus_cunning else "Disengage",
				mine and not _disengaged and (action_available or bonus_cunning), _disengaged, "", MOVES))
			actions.append(action("DODGE", "Dodge", mine and action_available, false, "", MOVES))
			actions.append(action("POTION", "Potion x%d" % h.potions, mine and action_available and h.potions > 0, armed_potion))
	return actions


## Buttons for the active hero's subclass features.
func _feature_actions(h: Hero) -> Array:
	var list := []
	var fight := phase == Phase.PLAYER_TURN
	var wounded := _wounded()
	if h.has_feature("DIVINE_SMITE") and fight:
		list.append(action("SMITE", "Smite %d" % h.uses("SMITE"), h.uses("SMITE") > 0, h.smite_ready))
	if h.has_feature("LAY_ON_HANDS"):
		list.append(action("LAY_ON_HANDS", "Lay Hands %d" % h.uses("LAY_ON_HANDS"),
			h.uses("LAY_ON_HANDS") > 0 and wounded and (not fight or action_available), armed_feature == "LAY_ON_HANDS"))
	if h.has_feature("CHALLENGE") and fight:
		list.append(action("CHALLENGE", "Challenge (B)", bonus_action_available, armed_feature == "CHALLENGE"))
	if h.has_feature("ENVENOM") and fight:
		list.append(action("ENVENOM", "Envenom %d" % h.uses("ENVENOM"),
			bonus_action_available and not h.envenomed and h.uses("ENVENOM") > 0, h.envenomed))
	if h.has_feature("HUNTERS_MARK") and fight:
		list.append(action("HUNTERS_MARK", "Hunt Mark (B)", bonus_action_available, armed_feature == "HUNTERS_MARK"))
	if h.has_feature("VOLLEY") and fight:
		list.append(action("VOLLEY", "Volley", action_available, armed_feature == "VOLLEY"))
	if h.has_feature("RAISE_DEAD"):
		var can_raise := _minions_of(h) < _max_minions(h) and h.lowest_available_slot(1) > 0 and \
			corpses.any(func(c): return dungeon.is_visible(c.x, c.y))
		list.append(action("RAISE_DEAD", "Raise Dead", can_raise and (not fight or action_available), armed_feature == "RAISE_DEAD"))
	if h.has_feature("PRESERVE_LIFE"):
		list.append(action("PRESERVE_LIFE", "Preserve Life", h.uses("PRESERVE_LIFE") > 0 and wounded and (not fight or action_available)))
	if h.has_feature("DESTRUCTIVE_WRATH") and fight:
		list.append(action("DESTRUCTIVE_WRATH", "Storm Wrath", h.uses("DESTRUCTIVE_WRATH") > 0, h.wrath_ready))
	if h.has_feature("OVERCHANNEL") and fight:
		list.append(action("OVERCHANNEL", "Overchannel", h.uses("OVERCHANNEL") > 0, h.overchannel_ready))
	return list


func _max_minions(h: Hero) -> int:
	return 2 if h.has_feature("UNDEAD_THRALLS") else 1


func _minions_of(h: Hero) -> int:
	return minions().filter(func(m): return m.master == h).size()


func _spell_label(_h: Hero, spell: String) -> String:
	var s: Dictionary = Rules.SPELLS[spell]
	if s["level"] != 0 and s["bonus_action"]:
		return "%s (B)" % s["short_name"]
	return s["short_name"]


func total_potions() -> int:
	var n := 0
	for h in living_heroes():
		n += h.potions
	return n


func party_gold() -> int:
	var n := 0
	for h in party:
		n += h.gold
	return n


func _disarm() -> void:
	armed_feature = ""
	armed_spell = ""
	armed_ranged = false
	armed_potion = false


# =================================================================================
# Player input
# =================================================================================

func perform(a: Dictionary) -> void:
	if not a["enabled"] or _auto_end_turn:
		return
	var h := active_hero()
	if h == null:
		return
	match a["id"]:
		"END_TURN": _end_hero_turn()
		"DESCEND": descend()
		"SHORT_REST": short_rest()
		"LONG_REST": long_rest()
		"DASH": _dash(h)
		"DODGE": _dodge(h)
		"DISENGAGE": _disengage(h)
		"SECOND_WIND": _second_wind(h)
		"ACTION_SURGE": _action_surge(h)
		"AIM": _aim(h)
		"POTION":
			var was_armed := armed_potion
			_disarm()
			armed_potion = not was_armed
		"RANGED":
			var was_armed := armed_ranged
			_disarm()
			armed_ranged = not was_armed
		"SMITE": h.smite_ready = not h.smite_ready
		"ENVENOM":
			if bonus_action_available and h.spend("ENVENOM"):
				bonus_action_available = false
				h.envenomed = true
				_log("%s coats their blade in poison." % h.name, "INFO")
		"DESTRUCTIVE_WRATH": h.wrath_ready = not h.wrath_ready
		"OVERCHANNEL": h.overchannel_ready = not h.overchannel_ready
		"PRESERVE_LIFE": _preserve_life(h)
		"LAY_ON_HANDS", "CHALLENGE", "HUNTERS_MARK", "VOLLEY", "RAISE_DEAD":
			var was_armed: bool = armed_feature == a["id"]
			_disarm()
			armed_feature = "" if was_armed else a["id"]
		"SPELL":
			var spell: String = a["spell"]
			if spell == "":
				return
			var was_armed := armed_spell == spell
			_disarm()
			match Rules.SPELLS[spell]["target"]:
				"ADJACENT_AREA", "PARTY": _cast_spell(h, spell, null, null)
				_: armed_spell = "" if was_armed else spell


## Player tapped a hero's portrait (or the hero on the map).
func select_hero(index: int) -> void:
	if index < 0 or index >= party.size():
		return
	var target: Hero = party[index]
	if phase == Phase.GAME_OVER or phase == Phase.MONSTER_TURN or _auto_end_turn:
		return
	var h := active_hero()
	if h == null:
		return
	var spell := armed_spell
	var spell_target: String = Rules.SPELLS[spell]["target"] if spell != "" else ""
	if target.dead:
		if spell_target == "DEAD_ALLY":
			armed_spell = ""
			_cast_spell(h, spell, null, target)
		return
	if armed_potion:
		armed_potion = false
		_give_potion(h, target)
	elif spell_target == "ALLY":
		armed_spell = ""
		_cast_spell(h, spell, null, target)
	elif armed_feature == "LAY_ON_HANDS":
		armed_feature = ""
		_lay_on_hands(h, target)
	elif phase == Phase.EXPLORING and index != leader_index and not target.is_unconscious():
		leader_index = index
		_walk_path.clear()
		_reset_trail()
		_log("%s takes the lead." % target.name, "INFO")


## Player tapped map square (x, y).
func tap(x: int, y: int) -> void:
	if phase == Phase.GAME_OVER or phase == Phase.MONSTER_TURN or _auto_end_turn:
		return
	var h := active_hero()
	if h == null or h.is_unconscious():
		return
	var spell_target: String = Rules.SPELLS[armed_spell]["target"] if armed_spell != "" else ""

	var ally := hero_at(x, y)
	if ally != null:
		if armed_potion or spell_target == "ALLY" or armed_feature == "LAY_ON_HANDS" or (phase == Phase.EXPLORING and ally != h):
			select_hero(party.find(ally))
			return

	var target := enemy_at(x, y)
	if target != null and not dungeon.is_visible(x, y):
		target = null
	if armed_feature != "":
		var feature := armed_feature
		armed_feature = ""
		_use_targeted_feature(h, feature, x, y, target)
		return
	if armed_spell != "":
		var spell := armed_spell
		_disarm()
		if target != null and (spell_target == "ENEMY" or spell_target == "ENEMY_AREA"):
			_cast_spell(h, spell, target, null)
		else:
			_log("Cancelled %s." % Rules.SPELLS[spell]["display_name"], "INFO")
		return
	if armed_ranged or armed_potion:
		var ranged := armed_ranged
		_disarm()
		if ranged and target != null:
			_ranged_attack(h, target)
		else:
			_log("Cancelled.", "INFO")
		return

	if target != null:
		if h.is_adjacent_to(target):
			_melee_attack(h, target)
			return
		if phase == Phase.PLAYER_TURN and can_attack() and _try_default_ranged_action(h, target):
			return
		var path = dungeon.find_path(h.x, h.y, x, y, _is_blocked_for_hero, true)
		if path == null:
			return
		_queue_walk(path, target)
		return

	if not dungeon.is_explored(x, y) or not dungeon.is_walkable(x, y):
		return
	var p = dungeon.find_path(h.x, h.y, x, y, _is_blocked_for_hero)
	if p == null:
		return
	_queue_walk(p, null)


# =================================================================================
# Inventory
# =================================================================================

## Shared party bag of unequipped magic items.
var bag: Array = []


## Gear can only be changed outside combat.
func can_change_equipment() -> bool:
	return phase == Phase.EXPLORING


func equip(hero: Hero, it: Dictionary) -> bool:
	if not can_change_equipment() or hero.dead or not bag.has(it) or not Items.usable_by(it, hero):
		return false
	bag.erase(it)
	var prev = hero.equip(it)
	if prev != null:
		bag.append(prev)
	_log("%s equips %s." % [hero.name, Items.name_for(it, hero)], "LOOT")
	return true


func unequip(hero: Hero, slot: String) -> bool:
	if not can_change_equipment():
		return false
	var it = hero.unequip(slot)
	if it == null:
		return false
	bag.append(it)
	return true


func _queue_walk(path: Array, target: Monster) -> void:
	_walk_path.clear()
	_walk_target = null
	if path.is_empty():
		return
	if phase == Phase.PLAYER_TURN and movement_left <= 0:
		_log("No movement left. Dash, or tap End Turn.", "INFO")
		return
	if phase == Phase.PLAYER_TURN:
		# Walk as far as movement allows, never stopping on an ally's square.
		var reachable := path.slice(0, movement_left)
		while not reachable.is_empty() and is_occupied(reachable.back().x, reachable.back().y):
			reachable.pop_back()
		if reachable.is_empty():
			_log("An ally is in the way.", "INFO")
			return
		_walk_path.assign(reachable)
	else:
		_walk_path.assign(path)
	_walk_target = target


## Heroes may path through allies (but not monsters or unexplored squares).
func _is_blocked_for_hero(x: int, y: int) -> bool:
	return enemy_at(x, y) != null or not dungeon.is_explored(x, y)


## Tapping a distant foe: rogues shoot, casters use their attack cantrip, and fighters
## shoot only when they can't reach melee this turn.
func _try_default_ranged_action(h: Hero, target: Monster) -> bool:
	var path = dungeon.find_path(h.x, h.y, target.x, target.y, _is_blocked_for_hero, true)
	var can_close_in: bool = path != null and path.size() <= movement_left
	var bow := h.ranged_weapon()
	if h.char_class == "ROGUE" or (not bow.is_empty() and not can_close_in):
		if not bow.is_empty() and h.distance_to(target) <= bow["range"] and _has_los(h, target):
			_ranged_attack(h, target)
			return true
	var cantrip := ""
	for s in h.known_spells:
		if Rules.SPELLS[s]["level"] == 0 and Rules.SPELLS[s]["target"] == "ENEMY":
			cantrip = s
			break
	if cantrip == "" or not action_available:
		return false
	if h.distance_to(target) <= Rules.SPELLS[cantrip]["range"] and _has_los(h, target):
		_cast_spell(h, cantrip, target, null)
		return true
	return false


func _has_los(a: Creature, b: Creature) -> bool:
	return dungeon.has_line_of_sight(a.x, a.y, b.x, b.y)


# =================================================================================
# Time
# =================================================================================

## Advances one animation step. Returns true if anything changed.
func tick() -> bool:
	match phase:
		Phase.EXPLORING:
			if not _walk_path.is_empty():
				_step_hero(leader())
				return true
			_idle_ticks += 1
			return _move_hunters_while_exploring() if _idle_ticks % 3 == 0 else false
		Phase.PLAYER_TURN:
			if _auto_end_turn:
				_auto_end_turn = false
				_advance_turn()
				return true
			if not _walk_path.is_empty():
				var c := current()
				if c is Hero:
					_step_hero(c)
				return true
			return false
		Phase.MONSTER_TURN:
			_monster_step()
			return true
	return false


func _step_hero(h: Hero) -> void:
	var next: Vector2i = _walk_path.pop_front()
	var is_last := _walk_path.is_empty()
	var ally := hero_at(next.x, next.y)
	var minion := monster_at(next.x, next.y)
	if minion != null and not minion.ally:
		minion = null
	# In combat you can pass through allies and minions but not stop on them; outside combat minions swap places.
	var blocked := enemy_at(next.x, next.y) != null or ((ally != null or minion != null) and phase == Phase.PLAYER_TURN and is_last)
	if blocked or not dungeon.can_step(h.x, h.y, next.x, next.y):
		_walk_path.clear()
		return
	if phase == Phase.PLAYER_TURN:
		if movement_left <= 0:
			_walk_path.clear()
			_log("No movement left.", "INFO")
			return
		var threats := _combat_monsters().filter(func(m): return m.is_adjacent_to(h) and \
			Creature.chebyshev(m.x, m.y, next.x, next.y) > 1 and m.reaction_available and not m.surprised and not m.asleep)
		movement_left -= 1
		_moved_this_turn = true
		h.move_to(next.x, next.y)
		if not _disengaged:
			for m in threats:
				if h.hp <= 0:
					break
				m.reaction_available = false
				_log("%s makes an opportunity attack on %s!" % [m.name, h.name], "BAD")
				_monster_attack(m, m.melee(), h)
			if h.hp <= 0 or phase == Phase.GAME_OVER:
				_walk_path.clear()
				if phase == Phase.PLAYER_TURN:
					_end_hero_turn()
				return
	else:
		var from := Vector2i(h.x, h.y)
		if minion != null:
			minion.move_to(from.x, from.y)
		h.move_to(next.x, next.y)
		_follow_leader(from)
		_minions_follow()
	_update_fov()
	_sound("STEP")
	_pick_up(h)
	if phase == Phase.EXPLORING:
		_move_hunters_while_exploring()
	else:
		_join_newcomers()
	if _walk_path.is_empty():
		var t := _walk_target
		_walk_target = null
		if t != null and phase == Phase.PLAYER_TURN and not t.is_dead() and h.is_adjacent_to(t) and can_attack():
			_melee_attack(h, t)
		if phase == Phase.EXPLORING and _on_stairs(h) and not _stairs_hint_shown:
			_stairs_hint_shown = true
			_log("You found the stairs down.", "GOOD")


# --- Marching order --------------------------------------------------------------

func _followers() -> Array:
	var l := leader()
	return living_heroes().filter(func(h): return h != l)


## The trail holds the square each follower is heading for, nearest first.
func _reset_trail() -> void:
	_trail.clear()
	for f in _followers():
		_trail.append(Vector2i(f.x, f.y))


## Followers snake along behind the leader, each taking the square the one ahead just left.
func _follow_leader(from: Vector2i) -> void:
	_trail.erase(Vector2i(leader().x, leader().y))
	_trail.push_front(from)
	var fs := _followers()
	while _trail.size() > fs.size():
		_trail.pop_back()
	for i in fs.size():
		if i >= _trail.size():
			continue
		var f: Hero = fs[i]
		var goal := _trail[i]
		if f.x == goal.x and f.y == goal.y:
			continue
		var step = null
		if dungeon.can_step(f.x, f.y, goal.x, goal.y):
			step = goal
		else:
			var p = dungeon.find_path(f.x, f.y, goal.x, goal.y, func(x, y): return monster_at(x, y) != null, false, 400)
			if p != null and not p.is_empty():
				step = p[0]
		if step != null and hero_at(step.x, step.y) == null and monster_at(step.x, step.y) == null:
			f.move_to(step.x, step.y)
			_pick_up(f)


## Skeleton minions trail after the leader outside combat.
func _minions_follow() -> void:
	var l := leader()
	for m in minions():
		if m.distance_to(l) <= 2:
			continue
		var p = dungeon.find_path(m.x, m.y, l.x, l.y, func(x, y): return enemy_at(x, y) != null, true, 600)
		if p == null:
			continue
		for step in p:
			if not is_occupied(step.x, step.y):
				if Creature.chebyshev(m.x, m.y, step.x, step.y) == 1:
					m.move_to(step.x, step.y)
				break


func _update_fov() -> void:
	dungeon.compute_fov(conscious_heroes().map(func(h): return [h.x, h.y, h.race_info()["sight"]]))


## Aware monsters hunt the party between fights; seeing any monster starts combat.
func _move_hunters_while_exploring() -> bool:
	var moved := false
	for m in monsters.filter(func(mm): return mm.aware and not mm.is_dead() and not mm.ally):
		var prey := _nearest(living_heroes(), m)
		if prey == null:
			break
		var path = dungeon.find_path(m.x, m.y, prey.x, prey.y, is_occupied, true, 600)
		if path != null and not path.is_empty():
			m.move_to(path[0].x, path[0].y)
			moved = true
	return _check_for_combat() or moved


## The creature in [param list] nearest to [param from] (first on ties), or null.
func _nearest(list: Array, from: Creature) -> Creature:
	var best: Creature = null
	for c in list:
		if best == null or c.distance_to(from) < best.distance_to(from):
			best = c
	return best


func _check_for_combat() -> bool:
	var seen := monsters.filter(func(m): return not m.is_dead() and not m.ally and dungeon.is_visible(m.x, m.y))
	if seen.is_empty():
		return false
	_start_combat(seen)
	return true


# =================================================================================
# Combat flow
# =================================================================================

func _start_combat(seen: Array) -> void:
	_walk_path.clear()
	_walk_target = null
	_disarm()
	turn_order.clear()
	_initiative_rolls.clear()

	var unaware := seen.filter(func(m): return not m.aware)
	if not unaware.is_empty():
		# A monster is surprised only if it notices no one: compare against the party's worst Stealth.
		var totals := []
		for h in conscious_heroes():
			var bonus: int = h.ability_mod("DEX") + (h.proficiency() if h.char_class == "ROGUE" else 0)
			var mode := Dice.Mode.ADVANTAGE if h.race == "THERIAN" else Dice.Mode.NORMAL
			totals.append([h, dice.d20(mode, h.lucky())["natural"] + bonus])
		var worst: Array = totals[0]
		for t in totals:
			if t[1] < worst[1]:
				worst = t
		_log("Stealth: " + ", ".join(totals.map(func(t): return "%s %d" % [t[0].name, t[1]])), "ROLL")
		var surprised := unaware.filter(func(m): return worst[1] > m.stats()["passive_perception"])
		for m in surprised:
			m.surprised = true
		if not surprised.is_empty():
			_log("You catch %s by surprise!" % _names(surprised), "GOOD")
		else:
			_log("%s is spotted!" % worst[0].name, "BAD")
	for m in seen:
		m.aware = true

	_log("Combat! %s. Roll initiative!" % _names(seen), "BAD")
	_sound("COMBAT_START")
	for m in monsters:
		m.acted = false
	var all := living_heroes() + seen + minions()
	for c in all:
		_initiative_rolls[c] = _roll_initiative(c)
	# Stable sort: higher roll, then higher bonus, then heroes first.
	var keyed := []
	for i in all.size():
		keyed.append([all[i], i])
	keyed.sort_custom(func(a, b):
		var ra: int = _initiative_rolls[a[0]]
		var rb: int = _initiative_rolls[b[0]]
		if ra != rb:
			return ra > rb
		var ba: int = a[0].initiative_bonus()
		var bb: int = b[0].initiative_bonus()
		if ba != bb:
			return ba > bb
		var ha := 0 if a[0] is Hero else 1
		var hb := 0 if b[0] is Hero else 1
		if ha != hb:
			return ha < hb
		return a[1] < b[1])
	for k in keyed:
		turn_order.append(k[0])
	_log("Initiative: " + ", ".join(turn_order.map(func(c): return "%s %d" % [c.name, _initiative_rolls[c]])), "ROLL")
	round_number = 1
	turn_index = 0
	_start_turn()


func _roll_initiative(c: Creature) -> int:
	return dice.d20(Dice.Mode.NORMAL, c is Hero and c.lucky())["natural"] + c.initiative_bonus()


## Monsters that come into view mid-fight join at the end of the order.
func _join_newcomers() -> void:
	var newcomers := monsters.filter(func(m): return not m.is_dead() and not m.ally and not turn_order.has(m) and dungeon.is_visible(m.x, m.y))
	for m in newcomers:
		m.aware = true
		_initiative_rolls[m] = _roll_initiative(m)
		turn_order.append(m)
		_log("%s joins the fight!" % m.name, "BAD")


func _start_turn() -> void:
	var c := current()
	if c == null:
		return
	c.dodging = false
	if c is Hero:
		phase = Phase.PLAYER_TURN
		movement_left = c.get_speed()
		action_available = true
		bonus_action_available = true
		_sneak_attack_used = false
		_aiming = false
		_disengaged = false
		_moved_this_turn = false
		attacks_left = 0
		_divine_strike_used = false
		c.reaction_available = true
		_disarm()
		if c.is_unconscious():
			_death_save(c)
			_update_fov()
			if phase == Phase.PLAYER_TURN and (c.is_unconscious() or c.dead):
				_auto_end_turn = true
	elif c is Monster:
		phase = Phase.MONSTER_TURN
		c.reaction_available = true
		_monster_steps_left = c.get_speed()
		_monster_done = false
		if c.surprised:
			_log("%s is surprised and loses its turn." % c.name, "INFO")
			_monster_done = true
		elif c.asleep:
			_log("%s is fast asleep." % c.name, "INFO")
			_monster_done = true


func _end_hero_turn() -> void:
	if phase != Phase.PLAYER_TURN:
		return
	_disarm()
	_walk_path.clear()
	_walk_target = null
	_advance_turn()


func _advance_turn() -> void:
	if phase == Phase.GAME_OVER:
		return
	var c := current()
	if c is Monster:
		c.surprised = false
		c.acted = true
	if _end_combat_if_over():
		return
	if _turn_holder_removed:
		_turn_holder_removed = false
	else:
		turn_index += 1
	if turn_index >= turn_order.size():
		turn_index = 0
		round_number += 1
		if _party_escaped():
			_end_combat("You slip away from your pursuers.")
			return
	_start_turn()


func _party_escaped() -> bool:
	for m in _combat_monsters():
		if dungeon.is_visible(m.x, m.y) or living_heroes().any(func(h): return h.distance_to(m) <= 10):
			return false
	return true


func _end_combat_if_over() -> bool:
	if phase == Phase.GAME_OVER or not _combat_monsters().is_empty():
		return false
	_sound("VICTORY")
	_end_combat("The fight is over.")
	return true


func _end_combat(message: String) -> void:
	_log(message, "GOOD")
	turn_order.clear()
	_initiative_rolls.clear()
	phase = Phase.EXPLORING
	_auto_end_turn = false
	_turn_holder_removed = false
	_walk_path.clear()
	_disarm()
	for h in living_heroes():
		h.blessed = false
	for h in living_heroes():
		if h.is_unconscious():
			h.heal(1)
			_log("The party tends to %s, who comes to with 1 HP." % h.name, "INFO")
	_ensure_living_leader()
	_reset_trail()
	_update_fov()
	_check_for_combat()


func _ensure_living_leader() -> void:
	if not leader().dead and not leader().is_unconscious():
		return
	for i in party.size():
		if not party[i].dead and not party[i].is_unconscious():
			leader_index = i
			return


func _monster_step() -> void:
	var c := current()
	var m: Monster = c as Monster if c is Monster else null
	if m == null or m.is_dead() or _monster_done:
		_advance_turn()
		return
	if m.ally:
		_ally_step(m)
		return
	var targets: Array = conscious_heroes() + minions()
	var ranged := m.ranged_attack()
	# A challenged monster turns on its challenger whenever it can.
	var challenger: Hero = m.challenger
	if challenger != null and (challenger.dead or challenger.is_unconscious()):
		challenger = null
	var pick := func(list: Array, key: Callable) -> Creature:
		if challenger != null and list.has(challenger):
			return challenger
		var best: Creature = null
		var best_score := 0
		for t in list:
			var s: int = key.call(t) * 100 + dice.roll(99) + (5000 if t is Monster else 0)
			if best == null or s < best_score:
				best = t
				best_score = s
		return best
	var adjacent := targets.filter(func(t): return m.is_adjacent_to(t))
	var shootable := [] if ranged.is_empty() else targets.filter(func(t): return m.distance_to(t) <= ranged["range"] and _has_los(m, t))
	if not adjacent.is_empty():
		_attack_creature(m, m.melee(), pick.call(adjacent, func(t): return t.hp))
		_monster_done = true
	elif not shootable.is_empty():
		_attack_creature(m, ranged, pick.call(shootable, func(t): return m.distance_to(t)))
		_monster_done = true
	elif _monster_steps_left > 0:
		var by_distance := targets.duplicate()
		_sort_by_distance(by_distance, m)
		var order := []
		if challenger != null:
			order.append(challenger)
		for t in by_distance:
			if not order.has(t):
				order.append(t)
		var step = null
		for t in order:
			step = _step_toward(m, t)
			if step != null:
				break
		if step != null:
			m.move_to(step.x, step.y)
			_monster_steps_left -= 1
		else:
			# Can't reach anyone standing: finish off a fallen hero within reach, if any.
			for h in living_heroes():
				if h.is_unconscious() and m.is_adjacent_to(h):
					_monster_attack(m, m.melee(), h)
					break
			_monster_done = true
	else:
		_monster_done = true
	# A finished monster hands over on the next tick, so its attack is visible for a moment.


## Stable sort of creatures by distance to [param from].
func _sort_by_distance(list: Array, from: Creature) -> void:
	var keyed := []
	for i in list.size():
		keyed.append([list[i], i])
	keyed.sort_custom(func(a, b):
		var da: int = a[0].distance_to(from)
		var db: int = b[0].distance_to(from)
		return da < db if da != db else a[1] < b[1])
	for i in keyed.size():
		list[i] = keyed[i][0]


## The creature in [param list] with the lowest [param key] (first on ties).
static func _min_by(list: Array, key: Callable):
	var best = null
	var best_key := 0
	for c in list:
		var k: int = key.call(c)
		if best == null or k < best_key:
			best = c
			best_key = k
	return best


func _step_toward(m: Monster, target: Creature):
	var p = dungeon.find_path(m.x, m.y, target.x, target.y, is_occupied, true, 900)
	return p[0] if p != null and not p.is_empty() else null


## A necromancer's skeleton: attack the nearest foe, or close in.
func _ally_step(m: Monster) -> void:
	var foes := _combat_monsters()
	var ranged := m.ranged_attack()
	var adjacent := foes.filter(func(f): return m.is_adjacent_to(f))
	var shootable := [] if ranged.is_empty() else foes.filter(func(f): return m.distance_to(f) <= ranged["range"] and _has_los(m, f))
	if foes.is_empty():
		_monster_done = true
	elif not adjacent.is_empty():
		_attack_creature(m, m.melee(), _min_by(adjacent, func(f): return f.hp))
		_monster_done = true
	elif not shootable.is_empty():
		_attack_creature(m, ranged, _min_by(shootable, func(f): return m.distance_to(f)))
		_monster_done = true
	elif _monster_steps_left > 0:
		_sort_by_distance(foes, m)
		var step = null
		for f in foes:
			step = _step_toward(m, f)
			if step != null:
				break
		if step == null:
			_monster_done = true
		else:
			m.move_to(step.x, step.y)
			_monster_steps_left -= 1
	else:
		_monster_done = true


func _attack_creature(m: Monster, attack: Dictionary, target: Creature) -> void:
	if target is Hero:
		_monster_attack(m, attack, target)
	else:
		_monster_attack_monster(m, attack, target)


static func _is_crit(roll: Dictionary) -> bool:
	return roll["natural"] == 20


static func _is_fumble(roll: Dictionary) -> bool:
	return roll["natural"] == 1


func _bolt(fx: int, fy: int, tx: int, ty: int, kind: String) -> void:
	_pending_fx.append({"type": "bolt", "from_x": fx, "from_y": fy, "to_x": tx, "to_y": ty, "kind": kind})


func _text_fx(x: int, y: int, text: String, kind: String) -> void:
	_pending_fx.append({"type": "text", "x": x, "y": y, "text": text, "kind": kind})


## Skeleton minions against monsters, and monsters against skeletons.
func _monster_attack_monster(m: Monster, attack: Dictionary, target: Monster) -> void:
	if attack["range"] > 1:
		_bolt(m.x, m.y, target.x, target.y, "ARROW")
	var roll := dice.d20(Dice.mode_of(target.asleep, target.dodging))
	var total: int = roll["natural"] + attack["to_hit"]
	var hit := _is_crit(roll) or (not _is_fumble(roll) and total >= target.get_ac())
	var verdict := "CRITICAL HIT!" if _is_crit(roll) else ("hit" if hit else "miss")
	_log("%s%s %s on %s: %s%s = %d vs AC %d, %s" % [m.name, " (yours)" if m.ally else "", attack["name"], target.name,
		Dice.describe(roll), _fmt(attack["to_hit"]), total, target.get_ac(), verdict], "ROLL" if hit == m.ally else "INFO")
	if not hit:
		_text_fx(target.x, target.y, "miss", "MISS")
		return
	var damage := maxi(1, dice.roll_expr(attack["damage"], _is_crit(roll)) + m.bonus_damage)
	_damage_monster(target, damage, _is_crit(roll), attack["damage_type"], attack["damage"])


## +1 AC from a paladin's Aura of Protection within 10 ft.
func _aura_paladin(target: Hero) -> Hero:
	for h in living_heroes():
		if h.has_feature("AURA_OF_PROTECTION") and not h.is_unconscious() and h.distance_to(target) <= 2:
			return h
	return null


# =================================================================================
# Attacks & damage
# =================================================================================

func _use_action() -> bool:
	if phase != Phase.PLAYER_TURN:
		return phase == Phase.EXPLORING
	if not action_available:
		var h := active_hero()
		_log("%s has already used their action. Tap End Turn." % (h.name if h != null else ""), "INFO")
		return false
	action_available = false
	return true


## Spends one attack: the first uses the action, Extra Attack gives more for free.
func _use_attack(h: Hero) -> bool:
	if phase != Phase.PLAYER_TURN:
		return false
	if attacks_left > 0:
		attacks_left -= 1
		return true
	if not _use_action():
		return false
	attacks_left = h.attacks_per_action() - 1
	return true


func _melee_attack(h: Hero, target: Monster) -> void:
	if phase != Phase.PLAYER_TURN or not _use_attack(h):
		return
	_weapon_attack(h, target, h.melee_weapon())


func _ranged_attack(h: Hero, target: Monster) -> void:
	if phase != Phase.PLAYER_TURN:
		return
	var weapon := h.ranged_weapon()
	if weapon.is_empty():
		return
	if h.distance_to(target) > weapon["range"]:
		_log("%s is out of range (%d squares)." % [target.name, weapon["range"]], "INFO")
		return
	if not _has_los(h, target):
		_log("No clear shot.", "INFO")
		return
	if not _use_attack(h):
		return
	_weapon_attack(h, target, weapon)


func _weapon_attack(h: Hero, target: Monster, weapon: Dictionary) -> void:
	if weapon["ranged"]:
		_bolt(h.x, h.y, target.x, target.y, "ARROW")
	var label := "%s's %s" % [h.name, ("+%d %s" % [h.weapon_bonus(), weapon["name"]]) if h.weapon_bonus() > 0 else weapon["name"]]
	var hit = _hero_attack_roll(h, target, h.attack_bonus(weapon), weapon["ranged"], label, true)
	if hit == null:
		return
	var crit: bool = hit["critical"]
	var damage := dice.roll_expr(weapon["damage"], crit) + h.damage_bonus(weapon)
	var detail := "%s%s" % [weapon["damage"], _fmt(h.damage_bonus(weapon))]
	# Sneak Attack: advantage, or an ally next to the target (and no disadvantage).
	var ally_adjacent := conscious_heroes().any(func(o): return o != h and o.is_adjacent_to(target))
	var mode: int = hit["roll"]["mode"]
	if h.char_class == "ROGUE" and not _sneak_attack_used and mode != Dice.Mode.DISADVANTAGE and \
			(mode == Dice.Mode.ADVANTAGE or ally_adjacent or target.surprised):
		_sneak_attack_used = true
		damage += dice.roll_expr(Dice.expr(h.sneak_attack_dice(), 6), crit)
		detail += " + Sneak Attack %dd6" % h.sneak_attack_dice()
	if target.hunted_by == h:
		damage += dice.roll_expr("1d6", crit)
		detail += " + Hunter's Mark 1d6"
	if h.envenomed:
		h.envenomed = false
		damage += dice.roll_expr("2d6", crit)
		detail += " + poison 2d6"
	if not weapon["ranged"] and h.smite_ready and h.spend("SMITE"):
		h.smite_ready = false
		var n := 3 if Rules.is_undead(target.type) else 2
		damage += dice.roll_expr(Dice.expr(n, 8), crit)
		detail += " + Divine Smite %dd8 radiant" % n
		_bolt(target.x, target.y - 1, target.x, target.y, "RADIANT")
	if h.has_feature("DIVINE_STRIKE") and not _divine_strike_used:
		_divine_strike_used = true
		var maxed := h.wrath_ready and h.spend("DESTRUCTIVE_WRATH")
		if maxed:
			h.wrath_ready = false
		damage += (8 * (2 if crit else 1)) if maxed else dice.roll_expr("1d8", crit)
		detail += " + Divine Strike 1d8 thunder" + (" (max)" if maxed else "")
	if h.has_feature("DEATH_STRIKE") and not target.acted:
		var dc := 8 + h.ability_mod("DEX") + h.proficiency()
		var save: int = dice.d20()["natural"] + target.save_bonus("CON")
		if save < dc:
			damage *= 2
			detail += ", Death Strike doubles it (CON %d vs DC %d)" % [save, dc]
	_damage_monster(target, maxi(1, damage), crit, weapon["damage_type"], detail)


## Rolls a hero's attack and logs it. Returns {"roll", "critical"} on a hit, or null on a miss.
func _hero_attack_roll(h: Hero, target: Monster, bonus: int, ranged: bool, label: String, weapon: bool = false):
	var assassinate := h.has_feature("ASSASSINATE") and not target.acted
	var advantage := target.marked or _aiming or target.asleep or assassinate
	var enemy_adjacent := _combat_monsters().any(func(m): return m.is_adjacent_to(h) and not m.asleep)
	var disadvantage := target.dodging or (ranged and enemy_adjacent)
	var roll := dice.d20(Dice.mode_of(advantage, disadvantage), h.lucky())
	target.marked = false
	_aiming = false
	var bless := dice.roll(4) if h.blessed else 0
	var total: int = roll["natural"] + bonus + bless
	var hit := _is_crit(roll) or (not _is_fumble(roll) and total >= target.get_ac())
	if not hit and weapon and h.spend("INDOMITABLE"):
		_log("%s is Indomitable and swings again!" % h.name, "GOOD")
		roll = dice.d20(Dice.mode_of(advantage, disadvantage), h.lucky())
		total = roll["natural"] + bonus + bless
		hit = _is_crit(roll) or (not _is_fumble(roll) and total >= target.get_ac())
	# Melee hits on a sleeping foe within reach are critical, as against any helpless creature.
	var critical := hit and (_is_crit(roll) or (target.asleep and not ranged and h.is_adjacent_to(target)) or \
		(h.has_feature("ASSASSINATE") and target.surprised))
	var mode_note := ""
	match roll["mode"]:
		Dice.Mode.ADVANTAGE: mode_note = " (adv)"
		Dice.Mode.DISADVANTAGE: mode_note = " (disadv)"
	var verdict := "miss"
	if critical:
		verdict = "CRITICAL HIT!"
	elif hit:
		verdict = "hit"
	elif _is_fumble(roll):
		verdict = "natural 1, miss"
	var bless_note := (" +bless %d" % bless) if h.blessed else ""
	_log("%s vs %s: %s%s%s = %d vs AC %d%s, %s" % [label, target.name, Dice.describe(roll), _fmt(bonus), bless_note, total,
		target.get_ac(), mode_note, verdict], "ROLL" if hit else "INFO")
	if not hit:
		_text_fx(target.x, target.y, "miss", "MISS")
		return null
	return {"roll": roll, "critical": critical}


func _damage_monster(target: Monster, amount: int, critical: bool, type: String, detail: String) -> void:
	if target.is_dead():
		return
	target.hp -= amount
	if target.asleep and target.hp > 0:
		target.asleep = false
		_log("%s wakes up!" % target.name, "INFO")
	_text_fx(target.x, target.y, "-%d" % amount, "CRIT" if critical else "DAMAGE")
	_log("%s takes %d %s damage (%s)." % [target.name, amount, type, detail], "GOOD")
	if target.hp <= 0 and target.type == "ZOMBIE" and type != "radiant" and not critical:
		var dc := 5 + amount
		var save: int = dice.d20()["natural"] + target.save_bonus("CON")
		if save >= dc:
			target.hp = 1
			_log("Undead Fortitude! The zombie rolls %d vs DC %d and refuses to fall." % [save, dc], "BAD")
			return
	if target.hp <= 0:
		_kill_monster(target)


func _remove_from_turn_order(c: Creature) -> void:
	var idx := turn_order.find(c)
	if idx < 0:
		return
	turn_order.remove_at(idx)
	if idx < turn_index:
		turn_index -= 1


func _kill_monster(m: Monster) -> void:
	m.hp = 0
	_remove_from_turn_order(m)
	monsters.erase(m)
	corpses.append(Vector2i(m.x, m.y))
	if m.ally:
		_log("Your %s collapses into a heap of bones." % m.name, "BAD")
		return
	kills += 1
	if _casting_hero != null:
		var caster := _casting_hero
		if caster.has_feature("GRIM_HARVEST") and _casting_slot >= 1:
			var healed := caster.heal(2 * _casting_slot)
			if healed > 0:
				_text_fx(caster.x, caster.y, "+%d" % healed, "HEAL")
				_log("Grim Harvest: %s drinks in the dying life force (+%d HP)." % [caster.name, healed], "GOOD")
	var living := living_heroes()
	var share: int = m.stats()["xp"] / maxi(1, living.size())
	_log("%s is slain! +%d XP each" % [m.name, share], "GOOD")
	for h in living:
		var before: int = h.level
		for msg in h.gain_xp(share):
			_log(msg, "LEVEL")
			_text_fx(h.x, h.y, "LEVEL UP", "HEAL")
		for lvl in range(before + 1, h.level + 1):
			_level_ups.append({"hero_index": party.find(h), "level": lvl, "hp_gain": h.hp_per_level(), "gains": h.level_gains(lvl)})
	if dungeon.item_at(m.x, m.y) == null and dungeon.is_walkable(m.x, m.y):
		var boss := m.type == "OGRE"
		if boss or dice.roll(100) <= 3 + int(m.stats()["cr"]) * 3:
			dungeon.items.append(Dungeon.make_item(m.x, m.y, "TREASURE", Items.random_item(dice, depth, boss)))
		elif dice.roll(12) <= 2:
			dungeon.items.append(Dungeon.make_item(m.x, m.y, "POTION"))
		elif dice.roll(12) <= 3:
			dungeon.items.append(Dungeon.make_item(m.x, m.y, "GOLD"))
	if phase == Phase.PLAYER_TURN:
		_end_combat_if_over()


func _monster_attack(m: Monster, attack: Dictionary, target: Hero) -> void:
	var ranged: bool = attack["range"] > 1
	if ranged:
		_bolt(m.x, m.y, target.x, target.y, "ARROW")
	var advantage := target.is_unconscious()
	var challenged: Hero = m.challenger
	if challenged != null and (challenged.dead or challenged.is_unconscious() or challenged == target):
		challenged = null
	var disadvantage := target.dodging or challenged != null
	var roll := dice.d20(Dice.mode_of(advantage, disadvantage))
	var total: int = roll["natural"] + attack["to_hit"]
	var ac := target.get_ac() + (1 if _aura_paladin(target) != null else 0)
	var hit := _is_crit(roll) or (not _is_fumble(roll) and total >= ac)
	var critical := hit and (_is_crit(roll) or (target.is_unconscious() and not ranged))
	var verdict := "CRITICAL HIT!" if critical else ("hit" if hit else "miss")
	_log("%s %s on %s: %s%s = %d vs AC %d%s, %s" % [m.name, attack["name"], target.name, Dice.describe(roll), _fmt(attack["to_hit"]),
		total, ac, " (challenged, disadv)" if challenged != null else "", verdict], "BAD" if hit else "INFO")
	# Knight's Warding Maneuver can turn a hit on an adjacent ally into a miss.
	if hit and not _is_crit(roll):
		for knight in living_heroes():
			if knight != target and knight.has_feature("WARDING_MANEUVER") and knight.reaction_available and \
					not knight.is_unconscious() and knight.is_adjacent_to(target):
				knight.reaction_available = false
				var ward := dice.roll(8)
				if total < ac + ward:
					hit = false
					_log("%s's Warding Maneuver adds %d to %s's AC: the blow glances off!" % [knight.name, ward, target.name], "GOOD")
				else:
					_log("%s tries to ward %s (+%d AC) but the blow still lands." % [knight.name, target.name, ward], "INFO")
				break
	if not hit:
		_text_fx(target.x, target.y, "miss", "MISS")
		return
	var damage := maxi(1, dice.roll_expr(attack["damage"], critical))
	if target.has_uncanny_dodge() and target.reaction_available and not target.is_unconscious():
		target.reaction_available = false
		damage = maxi(1, damage / 2)
		_log("%s uses Uncanny Dodge to halve the damage!" % target.name, "GOOD")
	_damage_hero(target, damage, critical, "%s from %s" % [attack["damage_type"], m.name])
	# Tempest cleric: lightning lashes back at melee attackers.
	if not ranged and not m.is_dead() and target.has_feature("WRATH_OF_THE_STORM") and target.reaction_available and \
			not target.is_unconscious() and not target.dead and target.spend("WRATH_OF_THE_STORM"):
		target.reaction_available = false
		var maxed := target.wrath_ready and target.spend("DESTRUCTIVE_WRATH")
		if maxed:
			target.wrath_ready = false
		var dmg := 16 if maxed else dice.roll_expr("2d8")
		_bolt(target.x, target.y, m.x, m.y, "LIGHTNING")
		_log("Wrath of the Storm! Lightning lashes at the %s%s." % [m.name, " (Destructive Wrath: maximum)" if maxed else ""], "GOOD")
		var saved := _monster_save(target, m, "DEX")
		_damage_monster(m, dmg / 2 if saved else dmg, false, "lightning", "2d8, halved" if saved else "2d8")


func _damage_hero(h: Hero, amount: int, critical: bool, source: String) -> void:
	_text_fx(h.x, h.y, "-%d" % amount, "CRIT" if critical else "DAMAGE")
	if h.is_unconscious():
		h.stable = false
		h.death_failures += 2 if critical else 1
		_log("A blow lands on the fallen %s! Death save failures: %d" % [h.name, h.death_failures], "BAD")
		if h.death_failures >= 3:
			_hero_dies(h, "%s has died." % h.name)
		return
	h.hp -= amount
	_log("%s takes %d %s." % [h.name, amount, source], "BAD")
	if h.hp <= 0:
		var overflow := -h.hp
		h.hp = 0
		if overflow >= h.get_max_hp():
			_hero_dies(h, "Massive damage! %s is killed outright." % h.name)
		else:
			h.death_successes = 0
			h.death_failures = 0
			_log("%s falls unconscious!" % h.name, "BAD")
			_sound("HERO_DOWN")
			_update_fov()
			_check_total_party_kill()


func _death_save(h: Hero) -> void:
	if not h.stable and h.has_wound_closure():
		h.stable = true
		_log("%s's Periapt of Wound Closure stabilises them." % h.name, "GOOD")
	if h.stable:
		_log("%s is stable but unconscious." % h.name, "INFO")
		return
	var natural: int = dice.d20(Dice.Mode.NORMAL, h.lucky())["natural"]
	var paladin := _aura_paladin(h)
	var aura := maxi(1, paladin.ability_mod("CHA")) if paladin != null else 0
	if aura > 0:
		_log("%s draws strength from the Aura of Protection (+%d)." % [h.name, aura], "INFO")
	if natural >= 2 and natural <= 19:
		natural = mini(19, natural + aura)
	if natural == 20:
		h.heal(1)
		_log("%s's death save: natural 20! Back on their feet with 1 HP!" % h.name, "GOOD")
	elif natural == 1:
		h.death_failures += 2
		_log("%s's death save: natural 1, two failures (%d/3)." % [h.name, h.death_failures], "BAD")
	elif natural >= 10:
		h.death_successes += 1
		_log("%s's death save: %d, success (%d/3)." % [h.name, natural, h.death_successes], "ROLL")
	else:
		h.death_failures += 1
		_log("%s's death save: %d, failure (%d/3)." % [h.name, natural, h.death_failures], "BAD")
	if h.death_failures >= 3:
		_hero_dies(h, "%s has died." % h.name)
	elif h.death_successes >= 3:
		h.stable = true
		_log("%s is stable." % h.name, "INFO")


func _hero_dies(h: Hero, message: String) -> void:
	_sound("HERO_DIE")
	h.dead = true
	h.hp = 0
	graves.append(Vector2i(h.x, h.y))
	if in_combat() and current() == h:
		_turn_holder_removed = true
	_remove_from_turn_order(h)
	_log(message, "BAD")
	# Their potions go to the survivors.
	var living := living_heroes()
	if not living.is_empty():
		living[0].potions += h.potions
	h.potions = 0
	_check_total_party_kill()


## The adventure ends when nobody in the party is left standing.
func _check_total_party_kill() -> void:
	if not conscious_heroes().is_empty():
		return
	phase = Phase.GAME_OVER
	_walk_path.clear()
	_auto_end_turn = false
	_disarm()
	_log("The whole party has fallen.", "BAD")
	_log("Reached depth %d, %d gold, %d foes slain." % [depth, party_gold(), kills], "INFO")


# =================================================================================
# Subclass features
# =================================================================================

func _use_targeted_feature(h: Hero, feature: String, x: int, y: int, target: Monster) -> void:
	match feature:
		"CHALLENGE":
			if target == null or h.distance_to(target) > 6:
				_log("Challenge a foe you can see within 30 ft.", "INFO")
				return
			if not bonus_action_available:
				return
			bonus_action_available = false
			for m in monsters:
				if m.challenger == h:
					m.challenger = null
			target.challenger = h
			_text_fx(target.x, target.y, "challenged", "INFO")
			_log("%s challenges the %s: \"Face me!\"" % [h.name, target.name], "GOOD")
		"HUNTERS_MARK":
			if target == null:
				_log("Mark a foe you can see.", "INFO")
				return
			if not bonus_action_available:
				return
			bonus_action_available = false
			for m in monsters:
				if m.hunted_by == h:
					m.hunted_by = null
			target.hunted_by = h
			_text_fx(target.x, target.y, "marked", "INFO")
			_log("%s marks the %s as their quarry." % [h.name, target.name], "GOOD")
		"VOLLEY":
			var bow := h.ranged_weapon()
			if bow.is_empty():
				return
			if target == null or h.distance_to(target) > bow["range"] or not _has_los(h, target):
				_log("Volley needs a visible target within %d ft." % (bow["range"] * 5), "INFO")
				return
			if not _use_action():
				return
			var victims := _combat_monsters().filter(func(v): return Creature.chebyshev(v.x, v.y, target.x, target.y) <= 1)
			_log("%s looses a volley of %d arrows!" % [h.name, victims.size()], "INFO")
			for v in victims:
				if not v.is_dead():
					_weapon_attack(h, v, bow)
		"RAISE_DEAD":
			var corpse := Vector2i(x, y)
			if not corpses.has(corpse) or not dungeon.is_visible(x, y) or Creature.chebyshev(h.x, h.y, x, y) > 6:
				_log("Tap a visible corpse within 30 ft.", "INFO")
				return
			if _minions_of(h) >= _max_minions(h):
				_log("%s can't control another skeleton." % h.name, "INFO")
				return
			var slot := h.lowest_available_slot(1)
			if slot == 0:
				_log("No spell slots left to raise the dead.", "INFO")
				return
			if in_combat() and not _use_action():
				return
			h.spend_slot(slot)
			corpses.erase(corpse)
			var skeleton := Monster.create("SKELETON", dice)
			skeleton.ally = true
			skeleton.master = h
			skeleton.aware = true
			if h.has_feature("UNDEAD_THRALLS"):
				skeleton.extra_hp = h.level
				skeleton.bonus_damage = h.proficiency()
			skeleton.hp = skeleton.get_max_hp()
			var spot := corpse
			if is_occupied(x, y):
				var near := _squares_near(corpse, 1)
				if near.is_empty():
					return
				spot = near[0]
			skeleton.move_to(spot.x, spot.y)
			monsters.append(skeleton)
			if in_combat():
				turn_order.append(skeleton)
				_initiative_rolls[skeleton] = 0
			_bolt(h.x, h.y, spot.x, spot.y, "NECROTIC")
			_text_fx(spot.x, spot.y, "rise!", "NECROTIC")
			_log("%s speaks a word of death; the bones knit into a skeleton servant (level %d slot)." % [h.name, slot], "LEVEL")


func _lay_on_hands(h: Hero, target: Hero) -> void:
	if in_combat() and h.distance_to(target) > 1:
		_log("Lay on Hands needs a touch: move next to %s." % target.name, "INFO")
		return
	var amount := mini(h.uses("LAY_ON_HANDS"), target.get_max_hp() - maxi(0, target.hp))
	if amount <= 0:
		_log("%s doesn't need healing." % target.name, "INFO")
		return
	if not _use_action():
		return
	h.spend("LAY_ON_HANDS", amount)
	var healed := target.heal(amount)
	_text_fx(target.x, target.y, "+%d" % healed, "HEAL")
	_log("%s lays hands on %s: +%d HP (%d left in the pool)." % [h.name, target.name, healed, h.uses("LAY_ON_HANDS")], "GOOD")
	_update_fov()


func _preserve_life(h: Hero) -> void:
	var pool := 5 * h.level
	var wounded := living_heroes().filter(func(w): return (not in_combat() or w.distance_to(h) <= 6) and w.hp < w.get_max_hp() / 2)
	# Stable sort by fraction of HP left.
	var keyed := []
	for i in wounded.size():
		keyed.append([wounded[i], i])
	keyed.sort_custom(func(a, b):
		var fa: float = float(a[0].hp) / a[0].get_max_hp()
		var fb: float = float(b[0].hp) / b[0].get_max_hp()
		return fa < fb if fa != fb else a[1] < b[1])
	if keyed.is_empty():
		_log("No one nearby is badly hurt (below half HP).", "INFO")
		return
	if h.uses("PRESERVE_LIFE") <= 0 or not _use_action():
		return
	h.spend("PRESERVE_LIFE")
	_log("%s channels divinity: Preserve Life!" % h.name, "GOOD")
	for k in keyed:
		var w: Hero = k[0]
		if pool <= 0:
			break
		var amount := mini(pool, w.get_max_hp() / 2 - maxi(0, w.hp))
		if amount <= 0:
			continue
		pool -= amount
		var healed := w.heal(amount)
		_text_fx(w.x, w.y, "+%d" % healed, "HEAL")
		_log("%s recovers %d HP." % [w.name, healed], "GOOD")
	_update_fov()


# =================================================================================
# Spells
# =================================================================================

## Context of the spell being cast, for Grim Harvest, Arcane Potency and maximised damage.
var _casting_hero: Hero = null
var _casting_slot := 0
var _cast_maximized := false
var _potency_left := false
var _blessed_healer_used := false
var _cast_spell_id := ""
var _cast_upcast := 0


func cast_spell(h: Hero, spell: String, target: Monster, ally: Hero) -> void:
	_cast_spell(h, spell, target, ally)


func _cast_spell(h: Hero, spell: String, target: Monster, ally: Hero) -> void:
	if not h.known_spells.has(spell) or h.is_unconscious() or h.dead or phase == Phase.MONSTER_TURN or phase == Phase.GAME_OVER:
		return
	if phase == Phase.PLAYER_TURN and current() != h:
		return
	var s: Dictionary = Rules.SPELLS[spell]
	if s["out_of_combat_only"] and in_combat():
		_log("%s takes too long to cast in combat." % s["display_name"], "INFO")
		return
	var slot := 0 if s["level"] == 0 else h.lowest_available_slot(s["level"])
	if s["level"] != 0 and slot == 0:
		_log("%s has no spell slots left for %s. Rest to recover them." % [h.name, s["display_name"]], "INFO")
		return
	match s["target"]:
		"ENEMY", "ENEMY_AREA":
			if target == null or target.is_dead() or phase != Phase.PLAYER_TURN:
				return
			if h.distance_to(target) > s["range"]:
				_log("%s is out of range for %s (%d)." % [target.name, s["display_name"], s["range"]], "INFO")
				return
			if not _has_los(h, target):
				_log("No line of sight.", "INFO")
				return
		"ALLY":
			if ally == null or ally.dead:
				return
			if in_combat() and h.distance_to(ally) > s["range"]:
				if s["range"] <= 1:
					_log("%s needs a touch: move next to %s." % [s["display_name"], ally.name], "INFO")
				else:
					_log("%s is out of range for %s." % [ally.name, s["display_name"]], "INFO")
				return
		"ADJACENT_AREA":
			var reach := maxi(1, s["radius"])
			if not _combat_monsters().any(func(m): return m.distance_to(h) <= reach):
				_log("No enemies within reach of %s." % s["display_name"], "INFO")
				return
		"PARTY":
			if phase != Phase.PLAYER_TURN and not s["out_of_combat_only"] and not Rules.spell_is_healing(spell):
				return
		"DEAD_ALLY":
			if ally == null or not ally.dead:
				return
			if party_gold() < Rules.REVIVIFY_COST:
				_log("Revivify needs a %d gp diamond; the party has %d gold." % [Rules.REVIVIFY_COST, party_gold()], "INFO")
				return
	if s["bonus_action"] and phase == Phase.PLAYER_TURN:
		if not bonus_action_available:
			_log("%s has already used their bonus action." % h.name, "INFO")
			return
		bonus_action_available = false
	elif not _use_action():
		return
	if slot > 0:
		h.spend_slot(slot)
	var upcast := maxi(0, slot - s["level"])
	_log("%s casts %s%s." % [h.name, s["display_name"], (" (level %d slot)" % slot) if slot > 0 else ""], "INFO")
	var damaging: bool = not Rules.spell_is_healing(spell) and not s["damage_type"] in ["sleep", "bless", "life"]
	_casting_hero = h
	_casting_slot = slot
	_cast_spell_id = spell
	_cast_upcast = upcast
	_blessed_healer_used = false
	_potency_left = damaging and h.has_feature("ARCANE_POTENCY")
	_cast_maximized = false
	if damaging and slot >= 1 and h.overchannel_ready and h.spend("OVERCHANNEL"):
		h.overchannel_ready = false
		_cast_maximized = true
		_log("%s overchannels the spell: maximum damage!" % h.name, "GOOD")
	elif s["damage_type"] in ["thunder", "lightning"] and h.wrath_ready and h.spend("DESTRUCTIVE_WRATH"):
		h.wrath_ready = false
		_cast_maximized = true
		_log("Destructive Wrath: the storm strikes with full fury!", "GOOD")
	_resolve_spell(h, spell, slot, upcast, target, ally)
	_casting_hero = null
	_casting_slot = 0
	_cast_maximized = false


## The spell's dice scaled for cantrip level and upcasting.
func _scaled(base: String) -> String:
	var s: Dictionary = Rules.SPELLS[_cast_spell_id]
	var count: int = Dice.parse(base)["count"]
	if s["level"] == 0:
		count *= _casting_hero.cantrip_dice()
	return Dice.with_count(base, count + _cast_upcast * s["upcast_dice"])


func _resolve_spell(h: Hero, spell: String, slot: int, upcast: int, target: Monster, ally: Hero) -> void:
	var s: Dictionary = Rules.SPELLS[spell]
	var base: String = s["dice"] if s["dice"] != null else "0d1"
	match spell:
		"FIRE_BOLT": _spell_attack(h, target, spell, _scaled(base), "FIRE")
		"ACID_ARROW": _spell_attack(h, target, spell, _scaled(base), "NECROTIC")
		"INFLICT_WOUNDS": _spell_attack(h, target, spell, _scaled(base), "NECROTIC", true)
		"SPIRIT_GUARDIANS":
			_area_damage(h, spell, _combat_monsters().filter(func(m): return m.distance_to(h) <= s["radius"]), _scaled(base), "RADIANT", h.x, h.y)
		"LIGHTNING_BOLT":
			# Extend the line from the caster through the target out to the spell's range, stopping at walls.
			var dist := maxi(1, h.distance_to(target))
			var ex: int = h.x + (target.x - h.x) * s["range"] / dist
			var ey: int = h.y + (target.y - h.y) * s["range"] / dist
			var path: Array[Vector2i] = []
			var pts := Dungeon.line(h.x, h.y, ex, ey)
			for i in range(1, pts.size()):
				if not Dungeon.transparent(dungeon.at(pts[i].x, pts[i].y)):
					break
				path.append(pts[i])
			var victims := _combat_monsters().filter(func(m): return path.has(Vector2i(m.x, m.y)))
			var end: Vector2i = path.back() if not path.is_empty() else Vector2i(target.x, target.y)
			_bolt(h.x, h.y, end.x, end.y, "LIGHTNING")
			_area_damage(h, spell, victims, _scaled(base), "LIGHTNING", h.x, h.y)
		"MASS_CURE_WOUNDS", "MASS_HEALING_WORD":
			for w in _party_in_range(h, spell):
				_heal_hero(h, w, _scaled(base), slot)
		"RAY_OF_FROST": _spell_attack(h, target, spell, _scaled(base), "FORCE")
		"SCORCHING_RAY":
			for i in 3 + upcast:
				if target.is_dead():
					break
				_spell_attack(h, target, spell, base, "FIRE")
		"GUIDING_BOLT":
			var hit := _spell_attack(h, target, spell, _scaled(base), "RADIANT")
			if hit and not target.is_dead():
				target.marked = true
				_log("%s glows with mystical light." % target.name, "INFO")
		"MAGIC_MISSILE":
			var darts := 3 + upcast
			for i in darts:
				_bolt(h.x, h.y, target.x, target.y, "FORCE")
			var total := 0
			for i in darts:
				total += _roll_spell_damage(base)
			_damage_monster(target, total, false, s["damage_type"], "%d darts x %s" % [darts, base])
		"SACRED_FLAME", "TOLL_THE_DEAD":
			if spell == "SACRED_FLAME":
				_bolt(target.x, target.y - 2, target.x, target.y, "RADIANT")
			else:
				_bolt(target.x, target.y, target.x, target.y, "FORCE")
			if not _monster_save(h, target, s["save_ability"]):
				var b := "1d12" if spell == "TOLL_THE_DEAD" and target.hp < target.get_max_hp() else base
				var dmg_dice := _scaled(b)
				_damage_monster(target, _roll_spell_damage(dmg_dice), false, s["damage_type"], dmg_dice)
		"BURNING_HANDS":
			_area_damage(h, spell, _combat_monsters().filter(func(m): return m.is_adjacent_to(h)), _scaled(base), "FIRE", h.x, h.y)
		"THUNDERWAVE":
			_area_damage(h, spell, _combat_monsters().filter(func(m): return m.is_adjacent_to(h)), _scaled(base), "LIGHTNING", h.x, h.y)
		"SHATTER", "FIREBALL", "CALL_LIGHTNING", "ICE_STORM", "CONE_OF_COLD", "FLAME_STRIKE":
			var center := target
			var victims := _combat_monsters().filter(func(m): return Creature.chebyshev(m.x, m.y, center.x, center.y) <= s["radius"])
			var fx := "FORCE"
			if spell in ["FIREBALL", "FLAME_STRIKE"]:
				fx = "FIRE"
			elif spell in ["CALL_LIGHTNING", "ICE_STORM", "CONE_OF_COLD"]:
				fx = "LIGHTNING"
			if spell in ["CALL_LIGHTNING", "FLAME_STRIKE", "ICE_STORM"]:
				_bolt(center.x, center.y - 3, center.x, center.y, fx)
			else:
				_bolt(h.x, h.y, center.x, center.y, fx)
			_area_damage(h, spell, victims, _scaled(base), fx, center.x, center.y)
		"SLEEP":
			var center := target
			var pool := dice.roll_expr(_scaled(base))
			_bolt(h.x, h.y, center.x, center.y, "FORCE")
			_log("Sleep rolls %d hit points' worth of slumber." % pool, "ROLL")
			var candidates := _combat_monsters().filter(func(m): return not m.asleep and Creature.chebyshev(m.x, m.y, center.x, center.y) <= s["radius"])
			var keyed := []
			for i in candidates.size():
				keyed.append([candidates[i], i])
			keyed.sort_custom(func(a, b): return a[0].hp < b[0].hp if a[0].hp != b[0].hp else a[1] < b[1])
			var any_asleep := false
			for k in keyed:
				var m: Monster = k[0]
				if m.hp > pool:
					break
				pool -= m.hp
				m.asleep = true
				any_asleep = true
				_text_fx(m.x, m.y, "Zzz", "INFO")
				_log("%s falls asleep." % m.name, "GOOD")
			if not any_asleep:
				_log("No one succumbs.", "INFO")
		"CURE_WOUNDS", "HEALING_WORD":
			_heal_hero(h, ally, _scaled(base), slot)
		"PRAYER_OF_HEALING":
			for w in living_heroes():
				_heal_hero(h, w, _scaled(base), slot)
		"BLESS":
			var blessed := _party_in_range(h, spell).filter(func(w): return not w.is_unconscious())
			for w in blessed:
				w.blessed = true
				_text_fx(w.x, w.y, "blessed", "RADIANT")
			_log("%s %s blessed." % [", ".join(blessed.map(func(w): return w.name)), "is" if blessed.size() == 1 else "are"], "GOOD")
		"REVIVIFY":
			_revivify(h, ally)


## Living heroes within the spell's range of the caster (everyone outside combat).
func _party_in_range(h: Hero, spell: String) -> Array:
	var r: int = Rules.SPELLS[spell]["range"]
	return living_heroes().filter(func(w): return not in_combat() or w.distance_to(h) <= r)


func _heal_hero(caster: Hero, target: Hero, heal_dice: String, slot: int) -> void:
	var disciple := (2 + slot) if caster.has_feature("DISCIPLE_OF_LIFE") else 0
	var healed := target.heal(dice.roll_expr(heal_dice) + caster.spell_mod() + disciple)
	_text_fx(target.x, target.y, "+%d" % healed, "HEAL")
	_log("%s recovers %d HP.%s" % [target.name, healed, (" (Disciple of Life +%d)" % disciple) if disciple > 0 else ""], "GOOD")
	if target != caster and caster.has_feature("BLESSED_HEALER") and _casting_hero == caster and not _blessed_healer_used:
		_blessed_healer_used = true
		var self_heal := caster.heal(2 + slot)
		if self_heal > 0:
			_text_fx(caster.x, caster.y, "+%d" % self_heal, "HEAL")
			_log("Blessed Healer: %s recovers %d HP." % [caster.name, self_heal], "GOOD")
	_update_fov()


## Rolls spell damage, honouring maximised casts and Arcane Potency (once per spell).
func _roll_spell_damage(dmg_dice: String, critical: bool = false) -> int:
	var e := Dice.parse(dmg_dice)
	var count: int = e["count"] * (2 if critical else 1)
	var total: int = (count * e["sides"] + e["bonus"]) if _cast_maximized else dice.roll_expr(dmg_dice, critical)
	if _potency_left and _casting_hero != null:
		_potency_left = false
		total += maxi(0, _casting_hero.spell_mod())
	return total


func _area_damage(h: Hero, spell: String, victims: Array, dmg_dice: String, fx: String, cx: int, cy: int) -> void:
	var s: Dictionary = Rules.SPELLS[spell]
	var damage := _roll_spell_damage(dmg_dice)
	if victims.is_empty():
		_log("The spell catches no one.", "INFO")
	for m in victims:
		if s["target"] == "ADJACENT_AREA":
			_bolt(cx, cy, m.x, m.y, fx)
		var saved := _monster_save(h, m, s["save_ability"])
		_damage_monster(m, damage / 2 if saved else damage, false, s["damage_type"], ("%s, halved" % dmg_dice) if saved else dmg_dice)


func _revivify(caster: Hero, target: Hero) -> void:
	var cost := Rules.REVIVIFY_COST
	var by_gold := party.duplicate()
	var keyed := []
	for i in by_gold.size():
		keyed.append([by_gold[i], i])
	keyed.sort_custom(func(a, b): return a[0].gold > b[0].gold if a[0].gold != b[0].gold else a[1] < b[1])
	for k in keyed:
		var pay := mini(cost, k[0].gold)
		k[0].gold -= pay
		cost -= pay
	graves.erase(Vector2i(target.x, target.y))
	target.revive()
	var near := _squares_near(Vector2i(caster.x, caster.y), 1)
	var spot: Vector2i = near[0] if not near.is_empty() else Vector2i(caster.x, caster.y)
	target.move_to(spot.x, spot.y)
	_reset_trail()
	_update_fov()
	_text_fx(target.x, target.y, "revived", "HEAL")
	_log("%s lays a diamond on %s's brow... %s breathes again!" % [caster.name, target.name, target.name], "LEVEL")


func _spell_attack(h: Hero, target: Monster, spell: String, dmg_dice: String, fx: String, melee: bool = false) -> bool:
	_bolt(h.x, h.y, target.x, target.y, fx)
	var hit = _hero_attack_roll(h, target, h.spell_attack_bonus(), not melee, "%s's %s" % [h.name, Rules.SPELLS[spell]["display_name"]])
	if hit == null:
		return false
	_damage_monster(target, _roll_spell_damage(dmg_dice, hit["critical"]), hit["critical"], Rules.SPELLS[spell]["damage_type"], dmg_dice)
	return true


## Returns true if the monster succeeds on its saving throw against the caster's spell DC.
func _monster_save(caster: Hero, m: Monster, ability: String) -> bool:
	var roll: int = dice.d20()["natural"]
	var bonus := m.save_bonus(ability)
	var total := roll + bonus
	var success := total >= caster.spell_save_dc()
	_log("%s %s save: d20 %d%s = %d vs DC %d, %s" % [m.name, ability, roll, _fmt(bonus), total, caster.spell_save_dc(),
		"success" if success else "fail"], "ROLL")
	if success:
		_text_fx(m.x, m.y, "save", "MISS")
	return success


# =================================================================================
# Other actions
# =================================================================================

## In combat a potion is an action, used on yourself or an adjacent ally; outside combat anyone can share.
func _give_potion(giver: Hero, target: Hero) -> void:
	var source: Hero = null
	if in_combat():
		source = giver if giver.potions > 0 else null
	else:
		for w in living_heroes():
			if source == null or w.potions > source.potions:
				source = w
	if source == null or source.potions <= 0:
		_log("No potions left.", "INFO")
		return
	if in_combat() and giver.distance_to(target) > 1:
		_log("%s is too far away to hand a potion to." % target.name, "INFO")
		return
	if not _use_action():
		return
	source.potions -= 1
	_sound("POTION")
	var healed := target.heal(dice.roll_expr("2d4+2"))
	_text_fx(target.x, target.y, "+%d" % healed, "HEAL")
	if target == giver:
		_log("%s drinks a potion of healing: +%d HP." % [target.name, healed], "GOOD")
	else:
		_log("%s gives %s a potion of healing: +%d HP." % [giver.name, target.name, healed], "GOOD")
	_update_fov()


func _dash(h: Hero) -> void:
	if h.char_class == "ROGUE" and h.level >= 2 and bonus_action_available:
		bonus_action_available = false
		_log("%s uses Cunning Action: Dash." % h.name, "INFO")
	elif not _use_action():
		return
	else:
		_log("%s Dashes." % h.name, "INFO")
	movement_left += h.get_speed()


func _disengage(h: Hero) -> void:
	if h.char_class == "ROGUE" and h.level >= 2 and bonus_action_available:
		bonus_action_available = false
		_log("%s uses Cunning Action: Disengage." % h.name, "INFO")
	elif not _use_action():
		return
	else:
		_log("%s Disengages: no opportunity attacks this turn." % h.name, "INFO")
	_disengaged = true


func _dodge(h: Hero) -> void:
	if not _use_action():
		return
	h.dodging = true
	_log("%s Dodges: attacks against them have disadvantage." % h.name, "INFO")


func _second_wind(h: Hero) -> void:
	if not bonus_action_available or not h.second_wind_available:
		return
	bonus_action_available = false
	h.second_wind_available = false
	var healed := h.heal(dice.roll_expr("1d10") + h.level)
	_text_fx(h.x, h.y, "+%d" % healed, "HEAL")
	_log("%s uses Second Wind: +%d HP." % [h.name, healed], "GOOD")
	if h.has_feature("RALLYING_CRY"):
		var rallied := living_heroes().filter(func(w): return w != h and w.distance_to(h) <= 6)
		for w in rallied:
			var amount: int = w.heal(h.level)
			if amount > 0:
				_text_fx(w.x, w.y, "+%d" % amount, "HEAL")
		if not rallied.is_empty():
			_log("%s's Rallying Cry steadies the party (+%d HP each)." % [h.name, h.level], "GOOD")


func _action_surge(h: Hero) -> void:
	if action_available or not h.action_surge_available:
		return
	h.action_surge_available = false
	action_available = true
	attacks_left = 0
	_log("%s uses Action Surge!" % h.name, "GOOD")


func _aim(h: Hero) -> void:
	if not bonus_action_available or _moved_this_turn:
		return
	bonus_action_available = false
	_aiming = true
	movement_left = 0
	_log("%s takes careful aim: advantage on the next attack." % h.name, "INFO")


func short_rest() -> void:
	if phase != Phase.EXPLORING:
		return
	var healed := 0
	for h in living_heroes():
		healed += h.short_rest(dice)
	_log("The party takes a short rest and recovers %d HP in total." % healed, "GOOD")
	_sound("REST")
	for h in living_heroes():
		if h.uses("ARCANE_RECOVERY") <= 0:
			continue
		var budget: int = (h.level + 1) / 2
		var recovered := 0
		for lvl in range(mini(budget, h.max_spell_level()), 0, -1):
			while budget >= lvl and h.recover_slot(lvl):
				budget -= lvl
				recovered += lvl
		if recovered > 0:
			h.spend("ARCANE_RECOVERY")
			_log("%s uses Arcane Recovery, regaining %d levels of spell slots." % [h.name, recovered], "GOOD")
	for h in living_heroes():
		_text_fx(h.x, h.y, "%d HD" % h.hit_dice, "HEAL")
	_wandering_monster_check(1, 6)


func long_rest() -> void:
	if phase != Phase.EXPLORING or long_rest_used:
		return
	long_rest_used = true
	for h in living_heroes():
		h.long_rest()
		_text_fx(h.x, h.y, "rested", "HEAL")
	_log("The party takes a long rest. HP, spell slots and features restored.", "GOOD")
	_sound("REST")
	_wandering_monster_check(1, 3)


func _wandering_monster_check(numerator: int, denominator: int) -> void:
	if not dice.chance(numerator, denominator):
		return
	var far := dungeon.rooms.filter(func(r):
		var c := Dungeon.room_center(r)
		return not dungeon.is_visible(c.x, c.y) and living_heroes().all(func(h): return Creature.chebyshev(c.x, c.y, h.x, h.y) > 8))
	if far.is_empty():
		return
	var spawned := _spawn_encounter(dice.pick(far), _monster_pool())
	for m in spawned:
		m.aware = true
	if not spawned.is_empty():
		_log("You hear something approaching...", "BAD")


func descend() -> void:
	if phase != Phase.EXPLORING or not _on_stairs(leader()):
		return
	_sound("DESCEND")
	depth += 1
	_enter_level()


func _pick_up(h: Hero) -> void:
	var it = dungeon.item_at(h.x, h.y)
	if it == null:
		return
	dungeon.items.erase(it)
	match it["kind"]:
		"POTION":
			h.potions += 1
			_log("%s finds a potion of healing." % h.name, "LOOT")
		"GOLD":
			var gold := dice.roll_n(depth + 1, 6)
			h.gold += gold
			_log("%s picks up %d gold." % [h.name, gold], "LOOT")
			_text_fx(h.x, h.y, "+%dg" % gold, "INFO")
		"CHEST":
			_open_chest(h)
		"TREASURE":
			if it["item"] != null:
				_take_treasure(h, it["item"])


func _take_treasure(h: Hero, it: Dictionary) -> void:
	bag.append(it)
	_log("%s finds %s (%s)! It goes in the party bag." % [h.name, Items.item_name(it), Rules.RARITIES[Items.rarity(it)]["label"].to_lower()], "LOOT")
	_text_fx(h.x, h.y, Items.item_name(it), "LOOT")


func _open_chest(h: Hero) -> void:
	_sound("CHEST")
	var gold := dice.roll_n(depth * 4, 10)
	h.gold += gold
	var found := ["%d gold" % gold]
	var treasure = null
	var r := dice.roll(20)
	if r <= 6:
		h.potions += 1
		found.append("a potion of healing")
	elif r <= 8:
		h.potions += 2
		found.append("two potions of healing")
	elif r <= 15:
		treasure = Items.random_item(dice, depth)
	_log("%s opens a chest: %s." % [h.name, ", ".join(found)], "LOOT")
	_text_fx(h.x, h.y, "+%dg" % gold, "INFO")
	if treasure != null:
		_take_treasure(h, treasure)


# =================================================================================
# Save & load
# =================================================================================

## Short description for save-slot lists.
func summary() -> String:
	return "Depth %d · " % depth + ", ".join(party.map(func(h): return ("%s (dead)" % h.name) if h.dead else ("%s L%d" % [h.name, h.level])))


static func _pt(p: Vector2i) -> Array:
	return [p.x, p.y]


func _ref(c: Creature) -> String:
	return ("h%d" % party.find(c)) if c is Hero else ("m%d" % monsters.find(c))


func _index_or_null(h: Hero):
	return party.find(h) if h != null else null


## The complete game state as a JSON-ready dictionary. Everything not saved is derived on load.
func to_save() -> Dictionary:
	var d := dungeon
	var tiles := PackedStringArray()
	for i in d.tiles.size():
		tiles.append(["W", "F", "S"][d.tiles[i]])
	var explored := PackedStringArray()
	for i in d.explored.size():
		explored.append("1" if d.explored[i] != 0 else "0")
	var log_tail := log_entries.slice(maxi(0, log_entries.size() - MAX_LOG))
	return {
		"version": SAVE_VERSION,
		"summary": summary(),
		"depth": depth,
		"phase": Phase.keys()[phase],
		"leaderIndex": leader_index,
		"longRestUsed": long_rest_used,
		"kills": kills,
		"stairsHintShown": _stairs_hint_shown,
		"trail": _trail.map(_pt),
		"corpses": corpses.map(_pt),
		"graves": graves.map(_pt),
		"log": log_tail.map(func(e): return [e["kind"], e["text"]]),
		"heroes": party.map(func(h): return h.to_save()),
		"bag": bag.map(func(it): return Items.to_save(it)),
		"monsters": monsters.map(func(m): return {
			"type": m.type, "maxHp": m.base_max_hp, "hp": m.hp, "x": m.x, "y": m.y,
			"ally": m.ally, "acted": m.acted, "master": _index_or_null(m.master), "extraHp": m.extra_hp, "bonusDamage": m.bonus_damage,
			"challenger": _index_or_null(m.challenger), "huntedBy": _index_or_null(m.hunted_by),
			"aware": m.aware, "surprised": m.surprised, "reaction": m.reaction_available, "asleep": m.asleep,
			"dodging": m.dodging, "marked": m.marked,
		}),
		"dungeon": {
			"width": d.width, "height": d.height,
			"tiles": "".join(tiles),
			"explored": "".join(explored),
			"rooms": d.rooms.map(func(r): return [r.position.x, r.position.y, r.size.x, r.size.y]),
			"items": d.items.map(func(it): return [it["x"], it["y"], it["kind"], Items.to_save(it["item"]) if it["item"] != null else null]),
			"start": _pt(d.start), "stairs": _pt(d.stairs),
		},
		"combat": {
			"turnOrder": turn_order.map(_ref),
			"initiative": turn_order.map(func(c): return _initiative_rolls.get(c, 0)),
			"turnIndex": turn_index, "round": round_number,
			"movementLeft": movement_left, "action": action_available, "bonus": bonus_action_available,
			"sneakUsed": _sneak_attack_used, "aiming": _aiming, "disengaged": _disengaged,
			"moved": _moved_this_turn, "autoEnd": _auto_end_turn, "holderRemoved": _turn_holder_removed,
			"monsterSteps": _monster_steps_left, "monsterDone": _monster_done,
		},
	}


func to_json() -> String:
	return JSON.stringify(to_save())


## Why the last [method from_save] or [method from_json] returned null.
static var last_error := ""


static func _vec(v) -> Vector2i:
	return Vector2i(int(v[0]), int(v[1]))


static func _hero_or_null(party_list: Array, v):
	if v == null:
		return null
	var i := int(v)
	return party_list[i] if i >= 0 and i < party_list.size() else null


static func _fail(msg: String) -> Game:
	last_error = msg
	return null


## Rebuilds a game from [method to_save] output. Returns null (see [member last_error]) on a bad or incompatible save.
static func from_save(o: Dictionary, d: Dice = null) -> Game:
	last_error = ""
	if not o.has("version") or int(o["version"]) < 1 or int(o["version"]) > SAVE_VERSION:
		return _fail("Save is from an incompatible version")
	var version := int(o["version"])
	var heroes := []
	for h in o.get("heroes", []):
		heroes.append(Hero.from_save(h, version))
	if heroes.is_empty():
		return _fail("Save has no heroes")
	var g := Game.new(heroes, d, false)

	var dj: Dictionary = o["dungeon"]
	var dg := Dungeon.new(int(dj["width"]), int(dj["height"]))
	var tiles: String = dj["tiles"]
	var explored: String = dj["explored"]
	if tiles.length() != dg.width * dg.height or explored.length() != tiles.length():
		return _fail("Corrupt map")
	for i in tiles.length():
		var t := "WFS".find(tiles[i])
		if t < 0:
			return _fail("Corrupt map")
		dg.tiles[i] = t
		dg.explored[i] = 1 if explored[i] == "1" else 0
	for r in dj["rooms"]:
		dg.rooms.append(Rect2i(int(r[0]), int(r[1]), int(r[2]), int(r[3])))
	for v in dj["items"]:
		var magic = Items.from_save(v[3]) if v.size() > 3 and v[3] != null else null
		dg.items.append(Dungeon.make_item(int(v[0]), int(v[1]), str(v[2]), magic))
	dg.start = _vec(dj["start"])
	dg.stairs = _vec(dj["stairs"])
	g.dungeon = dg

	for m in o["monsters"]:
		var mon := Monster.create(str(m["type"]), g.dice, int(m["maxHp"]))
		mon.hp = int(m["hp"])
		mon.move_to(int(m["x"]), int(m["y"]))
		mon.aware = bool(m["aware"])
		mon.surprised = bool(m["surprised"])
		mon.reaction_available = bool(m["reaction"])
		mon.asleep = bool(m.get("asleep", false))
		if m.has("ally"):
			mon.ally = bool(m["ally"])
			mon.acted = bool(m["acted"])
			mon.extra_hp = int(m["extraHp"])
			mon.bonus_damage = int(m["bonusDamage"])
			mon.challenger = _hero_or_null(heroes, m.get("challenger"))
			mon.master = _hero_or_null(heroes, m.get("master"))
			mon.hunted_by = _hero_or_null(heroes, m.get("huntedBy"))
		mon.dodging = bool(m["dodging"])
		mon.marked = bool(m["marked"])
		g.monsters.append(mon)

	for it in o.get("bag", []):
		g.bag.append(Items.from_save(it))
	g.depth = int(o["depth"])
	var phase_index := Phase.keys().find(str(o["phase"]))
	if phase_index < 0:
		return _fail("Corrupt phase")
	g.phase = phase_index
	g.leader_index = clampi(int(o["leaderIndex"]), 0, heroes.size() - 1)
	g.long_rest_used = bool(o["longRestUsed"])
	g.kills = int(o["kills"])
	g._stairs_hint_shown = bool(o["stairsHintShown"])
	for p in o["trail"]:
		g._trail.append(_vec(p))
	for p in o["corpses"]:
		g.corpses.append(_vec(p))
	for p in o["graves"]:
		g.graves.append(_vec(p))
	for e in o["log"]:
		g.log_entries.append({"text": str(e[1]), "kind": str(e[0])})

	var c: Dictionary = o["combat"]
	var inits: Array = c["initiative"]
	var order: Array = c["turnOrder"]
	for i in order.size():
		var ref: String = order[i]
		var idx := int(ref.substr(1))
		var list: Array = heroes if ref[0] == "h" else g.monsters
		if idx < 0 or idx >= list.size():
			return _fail("Corrupt combat state")
		var creature: Creature = list[idx]
		g.turn_order.append(creature)
		g._initiative_rolls[creature] = int(inits[i]) if i < inits.size() else 0
	g.turn_index = int(c["turnIndex"])
	g.round_number = int(c["round"])
	g.movement_left = int(c["movementLeft"])
	g.action_available = bool(c["action"])
	g.bonus_action_available = bool(c["bonus"])
	g._sneak_attack_used = bool(c["sneakUsed"])
	g._aiming = bool(c["aiming"])
	g._disengaged = bool(c["disengaged"])
	g._moved_this_turn = bool(c["moved"])
	g._auto_end_turn = bool(c["autoEnd"])
	g._turn_holder_removed = bool(c["holderRemoved"])
	g._monster_steps_left = int(c["monsterSteps"])
	g._monster_done = bool(c["monsterDone"])
	if g.in_combat() and g.turn_order.is_empty():
		return _fail("Corrupt combat state")

	g._update_fov()
	g._log("Game loaded.", "INFO")
	return g


static func from_json(text: String, d: Dice = null) -> Game:
	var parsed = JSON.parse_string(text)
	if not parsed is Dictionary:
		return _fail("Not a save file")
	return from_save(parsed, d)


# =================================================================================
# Helpers
# =================================================================================

func _log(text: String, kind: String) -> void:
	log_entries.append({"text": text, "kind": kind})
	while log_entries.size() > 200:
		log_entries.pop_front()


static func _fmt(bonus: int) -> String:
	if bonus == 0:
		return ""
	return "+%d" % bonus if bonus > 0 else str(bonus)


static func _names(ms: Array) -> String:
	var order := []
	var counts := {}
	for m in ms:
		if not counts.has(m.name):
			order.append(m.name)
			counts[m.name] = 0
		counts[m.name] += 1
	var parts := []
	for n in order:
		if counts[n] > 1:
			parts.append("%d %ss" % [counts[n], n])
		elif "aeiou".contains(n[0].to_lower()):
			parts.append("an %s" % n)
		else:
			parts.append("a %s" % n)
	return ", ".join(parts)
