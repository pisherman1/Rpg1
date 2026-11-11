class_name Session
extends RefCounted
## Holds the party builder, the running game and the save slots: one autosave plus numbered manual slots.

const AUTOSAVE := 0
const MANUAL_SLOTS := [1, 2, 3]
const DIR := "user://saves"

static var dice := Dice.new()
static var builder := Party.Builder.new(dice)
static var game: Game = null


static func start_game() -> void:
	game = Game.new(builder.build(), dice)
	autosave()


static func new_party() -> void:
	game = null
	builder = Party.Builder.new(dice)


## Saves the running game to the autosave slot; a fallen party's autosave is removed instead.
static func autosave() -> void:
	if game == null:
		return
	if game.phase == Game.Phase.GAME_OVER:
		delete(AUTOSAVE)
	else:
		save(AUTOSAVE, game)


static func _path(slot: int) -> String:
	return DIR + ("/autosave.json" if slot == AUTOSAVE else "/slot%d.json" % slot)


## Writes the game; returns "" or an error message.
static func save(slot: int, g: Game) -> String:
	DirAccess.make_dir_recursive_absolute(DIR)
	var data := g.to_save()
	data["savedAt"] = int(Time.get_unix_time_from_system() * 1000)
	var target := _path(slot)
	var tmp := target + ".tmp"
	var f := FileAccess.open(tmp, FileAccess.WRITE)
	if f == null:
		return "can't write (%s)" % error_string(FileAccess.get_open_error())
	f.store_string(JSON.stringify(data))
	f.close()
	# Write-then-rename so a crash mid-save never leaves a half-written file.
	var err := DirAccess.rename_absolute(tmp, target)
	return "" if err == OK else error_string(err)


## Loads a slot into [member game]; returns "" or an error message.
static func load_slot(slot: int) -> String:
	var path := _path(slot)
	if not FileAccess.file_exists(path):
		return "That slot is empty"
	var g := Game.from_json(FileAccess.get_file_as_string(path))
	if g == null:
		return Game.last_error
	game = g
	return ""


## {"summary", "saved_at" (unix ms)} for a slot, or null if empty.
static func info(slot: int):
	var path := _path(slot)
	if not FileAccess.file_exists(path):
		return null
	var parsed = JSON.parse_string(FileAccess.get_file_as_string(path))
	if not parsed is Dictionary:
		return {"summary": "Unreadable save", "saved_at": FileAccess.get_modified_time(path) * 1000}
	return {"summary": str(parsed.get("summary", "Saved game")),
		"saved_at": int(parsed.get("savedAt", FileAccess.get_modified_time(path) * 1000))}


static func delete(slot: int) -> void:
	if FileAccess.file_exists(_path(slot)):
		DirAccess.remove_absolute(_path(slot))
