extends SceneTree
## Headless test runner: godot --headless --path godot --script res://tests/run_tests.gd
## Runs every test_* method of the suites below and exits non-zero on failure.

const SUITES := [
	"res://tests/test_smoke.gd",
]

var failures: Array[String] = []
var current := ""


func _initialize() -> void:
	var total := 0
	for path in SUITES:
		var suite = load(path).new()
		suite.runner = self
		for m in suite.get_method_list():
			var name: String = m["name"]
			if not name.begins_with("test_"):
				continue
			current = "%s.%s" % [path.get_file().get_basename(), name]
			total += 1
			var before := failures.size()
			suite.call(name)
			print("%s %s" % ["PASS" if failures.size() == before else "FAIL", current])
	print("\n%d tests, %d failures" % [total, failures.size()])
	for f in failures:
		printerr(f)
	quit(1 if failures.size() > 0 else 0)


func fail(message: String) -> void:
	failures.append("%s: %s" % [current, message])
