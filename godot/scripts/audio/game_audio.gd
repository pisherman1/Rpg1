class_name GameAudio
extends Node
## Plays the synthesised sound effects (a small voice pool) and music (with crossfades).
## The sounds were rendered from the Android version's synthesiser, so both versions sound alike.

const MUSIC_VOLUME := 0.55
const FADE_TIME := 0.7
const VOICES := 10
const LOOPING := {"TITLE": true, "EXPLORE": true, "COMBAT": true, "DEFEAT": false}
const VARIED := ["STEP", "HIT", "MISS", "BOW", "COIN", "MONSTER_DIE"]
const SETTINGS_PATH := "user://settings.cfg"

var music_on := true:
	set(v):
		music_on = v
		_save_settings()
		_apply_music()
var sound_on := true:
	set(v):
		sound_on = v
		_save_settings()

var _sfx := {}
var _voices: Array[AudioStreamPlayer] = []
var _next_voice := 0
var _last_played := {}
var _tracks := {}
var _player: AudioStreamPlayer = null
var _playing := ""
var _wanted := ""


func _ready() -> void:
	var cfg := ConfigFile.new()
	if cfg.load(SETTINGS_PATH) == OK:
		music_on = cfg.get_value("audio", "music", true)
		sound_on = cfg.get_value("audio", "sound", true)
	for i in VOICES:
		var p := AudioStreamPlayer.new()
		add_child(p)
		_voices.append(p)


func _save_settings() -> void:
	if not is_inside_tree():
		return
	var cfg := ConfigFile.new()
	cfg.set_value("audio", "music", music_on)
	cfg.set_value("audio", "sound", sound_on)
	cfg.save(SETTINGS_PATH)


func _sample(id: String) -> AudioStream:
	if not _sfx.has(id):
		var path := "res://assets/audio/sfx_%s.wav" % id.to_lower()
		_sfx[id] = load(path) if ResourceLoader.exists(path) else null
	return _sfx[id]


func play(id: String) -> void:
	if not sound_on:
		return
	var stream := _sample(id)
	if stream == null:
		return
	var now := Time.get_ticks_msec()
	var gap := 190 if id == "STEP" else 40
	if now - int(_last_played.get(id, -100000)) < gap:
		return
	_last_played[id] = now
	var p := _voices[_next_voice]
	_next_voice = (_next_voice + 1) % VOICES
	p.stream = stream
	var volume := 0.35 if id == "STEP" else (0.5 if id == "CLICK" else 0.9)
	p.volume_db = linear_to_db(volume)
	# A little pitch variety keeps repeated sounds from feeling mechanical.
	p.pitch_scale = randf_range(0.92, 1.08) if id in VARIED else 1.0
	p.play()


## Requests a track ("" for silence); switching crossfades.
func set_music(track: String) -> void:
	if track == _wanted:
		return
	_wanted = track
	_apply_music()


func _track(id: String) -> AudioStream:
	if not _tracks.has(id):
		var s = load("res://assets/audio/music_%s.ogg" % id.to_lower())
		if s is AudioStreamOggVorbis:
			s.loop = LOOPING.get(id, true)
		_tracks[id] = s
	return _tracks[id]


func _apply_music() -> void:
	if not is_inside_tree():
		return
	var target := _wanted if music_on else ""
	if target == _playing:
		return
	if _player != null:
		var old := _player
		var t := create_tween()
		t.tween_property(old, "volume_db", -60.0, FADE_TIME)
		t.tween_callback(old.queue_free)
	_player = null
	_playing = target
	if target == "":
		return
	var stream := _track(target)
	if stream == null:
		return
	var p := AudioStreamPlayer.new()
	p.stream = stream
	p.volume_db = -60.0
	add_child(p)
	p.play()
	create_tween().tween_property(p, "volume_db", linear_to_db(MUSIC_VOLUME), FADE_TIME)
	_player = p


func _notification(what: int) -> void:
	# Pause sound while the app is in the background.
	if what == NOTIFICATION_APPLICATION_PAUSED or what == NOTIFICATION_APPLICATION_FOCUS_OUT:
		AudioServer.set_bus_mute(0, true)
	elif what == NOTIFICATION_APPLICATION_RESUMED or what == NOTIFICATION_APPLICATION_FOCUS_IN:
		AudioServer.set_bus_mute(0, false)
