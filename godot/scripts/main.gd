extends Node
## Entry point: the 3D dungeon under the game UI, plus audio and the portrait studio.

var ui: GameUI


func _ready() -> void:
	var audio := GameAudio.new()
	add_child(audio)
	var studio := PortraitStudio.new()
	add_child(studio)
	var world := DungeonView.new()
	add_child(world)
	var root := Control.new()
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(root)
	ui = GameUI.new()
	ui.world = world
	ui.audio = audio
	ui.studio = studio
	root.add_child(ui)
	# The root Control's parent is a plain Node, so size it to the window ourselves.
	get_viewport().size_changed.connect(_fit_to_window)
	_fit_to_window()


func _fit_to_window() -> void:
	var root: Control = ui.get_parent()
	root.position = Vector2.ZERO
	root.size = get_viewport().get_visible_rect().size
	ui.size = root.size


func _notification(what: int) -> void:
	if what == NOTIFICATION_WM_GO_BACK_REQUEST or what == NOTIFICATION_WM_CLOSE_REQUEST:
		if what == NOTIFICATION_WM_GO_BACK_REQUEST and ui != null and ui.handle_back():
			return
		# Leaving the app: keep the adventure safe.
		Session.autosave()
		get_tree().quit()
	elif what == NOTIFICATION_APPLICATION_PAUSED:
		Session.autosave()
