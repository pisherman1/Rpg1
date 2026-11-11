extends Node
## Entry point: the world view under the game UI, plus audio.

var ui: GameUI


func _ready() -> void:
	var audio := GameAudio.new()
	add_child(audio)
	var root := Control.new()
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(root)
	var world := WorldView.new()
	root.add_child(world)
	ui = GameUI.new()
	ui.world = world
	ui.audio = audio
	root.add_child(ui)


func _notification(what: int) -> void:
	if what == NOTIFICATION_WM_GO_BACK_REQUEST or what == NOTIFICATION_WM_CLOSE_REQUEST:
		if what == NOTIFICATION_WM_GO_BACK_REQUEST and ui != null and ui.handle_back():
			return
		# Leaving the app: keep the adventure safe.
		Session.autosave()
		get_tree().quit()
	elif what == NOTIFICATION_APPLICATION_PAUSED:
		Session.autosave()
