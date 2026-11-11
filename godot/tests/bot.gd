extends RefCounted
## Drives a game with a simple bot, for smoke and balance tests.

const ScriptedFail := "Bot stuck"

## Set when the bot made no progress for too long; tests report it.
var stuck_report := ""


## Plays until game over, [param max_depth] or [param max_ticks]. Returns ticks played.
func play(game: Game, max_ticks: int, max_depth: int = 6) -> int:
	var ticks := 0
	var stuck := 0
	while ticks < max_ticks and game.phase != Game.Phase.GAME_OVER and game.depth < max_depth:
		ticks += 1
		if game.is_busy():
			game.tick()
			continue
		var before := snapshot(game)
		decide(game)
		game.tick()
		stuck = stuck + 1 if snapshot(game) == before else 0
		if stuck > 200:
			var c := game.current()
			stuck_report = "Bot stuck: phase=%s depth=%d current=%s idx=%d/%d move=%d actions=%s" % [
				Game.Phase.keys()[game.phase], game.depth, c.name if c != null else "-", game.turn_index,
				game.turn_order.size(), game.movement_left,
				str(game.available_actions().filter(func(a): return a["enabled"]).map(func(a): return a["label"]))]
			return ticks
	return ticks


func snapshot(g: Game) -> String:
	var parts := [g.phase, g.turn_index, g.round_number, g.depth]
	for h in g.party:
		parts.append_array([h.x, h.y, h.hp])
	var mhp := 0
	var mpos := 0
	for m in g.monsters:
		mhp += m.hp
		mpos += m.x * 31 + m.y
	parts.append_array([mhp, mpos, g.log_entries.back()["text"] if not g.log_entries.is_empty() else ""])
	return str(parts)


## Spends level-up choices and equips loot the simple way.
func manage_party(game: Game) -> void:
	for h in game.living_heroes():
		if h.needs_subclass():
			h.choose_subclass(Rules.subclasses_for(h.char_class)[game.party.find(h) % 2])
		while h.pending_ability_points > 0:
			var pick := ""
			for a in h.cls()["priority"]:
				if h.base_abilities[a] < 20:
					pick = a
					break
			if pick == "" or not h.improve_abilities({pick: 1}):
				break
		while h.pending_spells > 0:
			var best := ""
			for s in h.learnable_spells():
				if best == "" or Rules.SPELLS[s]["level"] > Rules.SPELLS[best]["level"]:
					best = s
			if best == "" or not h.learn_spell(best):
				break
	if game.can_change_equipment():
		for it in game.bag.duplicate():
			for w in game.living_heroes():
				var cur = w.item(Items.slot(it))
				if Items.usable_by(it, w) and (cur == null or cur["bonus"] < it["bonus"]):
					game.equip(w, it)
					break


func _act(game: Game, actions: Array, id: String, spell: String = "") -> bool:
	for a in actions:
		if a["id"] == id and a["enabled"] and (spell == "" or a["spell"] == spell):
			game.perform(a)
			return true
	return false


func decide(game: Game) -> void:
	manage_party(game)
	var hero := game.active_hero()
	if hero == null:
		return
	var actions := game.available_actions()
	match game.phase:
		Game.Phase.EXPLORING:
			var hurt := game.living_heroes().filter(func(h): return h.hp < h.get_max_hp() / 2)
			if not hurt.is_empty():
				if _act(game, actions, "SHORT_REST") and game.living_heroes().all(func(h): return h.hp >= h.get_max_hp() / 2):
					return
				if _act(game, actions, "LONG_REST"):
					return
			if _act(game, actions, "DESCEND"):
				return
			var goal = nearest_frontier(game)
			if goal == null:
				goal = game.dungeon.stairs
			game.tap(goal.x, goal.y)
		Game.Phase.PLAYER_TURN:
			if hero.is_unconscious():
				return
			# Help a dying ally first if one is in reach.
			var dying: Hero = null
			for h in game.living_heroes():
				if h.is_unconscious() and h.distance_to(hero) <= 1:
					dying = h
					break
			if dying != null and game.action_available:
				if _act(game, actions, "SPELL", "CURE_WOUNDS") or _act(game, actions, "POTION"):
					game.select_hero(game.party.find(dying))
					if not game.action_available:
						return
			if hero.hp <= hero.get_max_hp() / 3:
				if _act(game, actions, "SECOND_WIND"):
					return
				if _act(game, actions, "SPELL", "CURE_WOUNDS") or _act(game, actions, "POTION"):
					game.select_hero(game.party.find(hero))
					if not game.action_available:
						return
			var foes := game.monsters.filter(func(m): return not m.ally)
			var target: Monster = Game._min_by(foes.filter(func(m): return game.dungeon.is_visible(m.x, m.y)), func(m): return m.distance_to(hero))
			if target == null:
				target = Game._min_by(foes, func(m): return m.distance_to(hero))
			if target == null:
				_act(game, actions, "END_TURN")
				return
			_use_features(game, hero, target)
			if game.action_available:
				# Prefer the strongest known attack spell with a slot left.
				var spell := ""
				for s in hero.known_spells:
					var info: Dictionary = Rules.SPELLS[s]
					if info["level"] > 0 and not Rules.spell_is_healing(s) and info["target"] in ["ENEMY", "ENEMY_AREA"] and game.can_cast_now(hero, s):
						if spell == "" or info["level"] > Rules.SPELLS[spell]["level"]:
							spell = s
				if spell != "" and target.distance_to(hero) <= Rules.SPELLS[spell]["range"] and game.dungeon.is_visible(target.x, target.y):
					_act(game, game.available_actions(), "SPELL", spell)
					game.tap(target.x, target.y)
					if not game.action_available:
						return
				game.tap(target.x, target.y)
				if game.is_busy():
					return
				if _act(game, game.available_actions(), "ACTION_SURGE"):
					game.tap(target.x, target.y)
					return
			elif not target.is_adjacent_to(hero) and game.movement_left > 0:
				var before := Vector2i(hero.x, hero.y)
				game.tap(target.x, target.y)
				if game.is_busy() or before != Vector2i(hero.x, hero.y):
					return
			if not game.is_busy():
				_act(game, game.available_actions(), "END_TURN")


## Exercises subclass features when they're available.
func _use_features(game: Game, hero: Hero, target: Monster) -> void:
	var actions := game.available_actions()
	var ids := {}
	for a in actions:
		if a["enabled"]:
			ids[a["id"]] = true
	var visible := game.dungeon.is_visible(target.x, target.y)
	if ids.has("SMITE") and not hero.smite_ready:
		_act(game, actions, "SMITE")
	if ids.has("DESTRUCTIVE_WRATH") and not hero.wrath_ready:
		_act(game, actions, "DESTRUCTIVE_WRATH")
	if ids.has("OVERCHANNEL") and not hero.overchannel_ready:
		_act(game, actions, "OVERCHANNEL")
	if ids.has("ENVENOM"):
		_act(game, actions, "ENVENOM")
	if ids.has("CHALLENGE") and target.challenger == null and visible and hero.distance_to(target) <= 6:
		_act(game, actions, "CHALLENGE")
		game.tap(target.x, target.y)
	if ids.has("HUNTERS_MARK") and target.hunted_by != hero and visible:
		_act(game, game.available_actions(), "HUNTERS_MARK")
		game.tap(target.x, target.y)
	if ids.has("PRESERVE_LIFE") and game.living_heroes().filter(func(h): return h.hp < h.get_max_hp() / 2).size() >= 2:
		_act(game, game.available_actions(), "PRESERVE_LIFE")
	if ids.has("RAISE_DEAD"):
		for c in game.corpses:
			if game.dungeon.is_visible(c.x, c.y) and Creature.chebyshev(c.x, c.y, hero.x, hero.y) <= 6 and not game.is_occupied(c.x, c.y):
				_act(game, game.available_actions(), "RAISE_DEAD")
				game.tap(c.x, c.y)
				break
	if ids.has("VOLLEY") and game.monsters.filter(func(m): return not m.ally and Creature.chebyshev(m.x, m.y, target.x, target.y) <= 1).size() >= 2:
		_act(game, game.available_actions(), "VOLLEY")
		game.tap(target.x, target.y)


func nearest_frontier(game: Game):
	var d := game.dungeon
	var h := game.leader()
	var best = null
	var best_dist := 1 << 30
	var blocked := func(px, py): return game.enemy_at(px, py) != null or not d.is_explored(px, py)
	for x in d.width:
		for y in d.height:
			if not d.is_explored(x, y) or not d.is_walkable(x, y):
				continue
			var frontier := false
			for dir in Dungeon.DIRS:
				if d.in_bounds(x + dir.x, y + dir.y) and not d.is_explored(x + dir.x, y + dir.y):
					frontier = true
					break
			if not frontier and d.item_at(x, y) == null:
				continue
			var dist := Creature.chebyshev(x, y, h.x, h.y)
			if dist >= 1 and dist < best_dist and d.find_path(h.x, h.y, x, y, blocked) != null:
				best = Vector2i(x, y)
				best_dist = dist
	return best
