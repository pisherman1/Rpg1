extends Node
## Entry point; builds the game screen.

func _ready() -> void:
	var label := Label.new()
	label.text = "Dungeon Delver 2"
	add_child(label)
