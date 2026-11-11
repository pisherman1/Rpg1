extends RefCounted
## Assertion helpers shared by test suites.

var runner


func check(condition: bool, message: String = "check failed") -> void:
	if not condition:
		runner.fail(message)


func check_eq(actual, expected, message: String = "") -> void:
	if actual != expected:
		runner.fail("%s expected <%s> but was <%s>" % [message, str(expected), str(actual)])
