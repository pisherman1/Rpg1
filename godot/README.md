# Dungeon Delver 2 (Godot)

A Godot 4.5 version of Dungeon Delver in 3D: the same D&D 5e-style rules, party of four, subclasses,
loot, saves and chiptune audio as the Android/Kotlin version in the parent folder, rebuilt as a lit 3D
dungeon with animated low-poly heroes and monsters. Uses the Mobile (Vulkan) renderer.

## Opening it

1. Install **Godot 4.5** (standard build; GDScript only, no .NET needed).
2. In the Project Manager choose **Import** and pick `godot/project.godot`.
3. Press **F5** to play. Mouse clicks act as taps.

## Building the Android APK

CI (`.github/workflows/godot.yml`) runs the tests and exports a debug APK as the
`dungeon-delver-2-apk` artifact on every push that touches `godot/`.

To export yourself: install the Android export templates (Editor → Manage Export Templates),
set the Android SDK and Java paths in Editor Settings → Export → Android, then
Project → Export → **Android**. The app id is `com.rpg1.dungeondelver2`, so it installs
alongside the original game.

## Layout

| Path | What |
| --- | --- |
| `scripts/core/` | Rules engine: `rules.gd` (data tables), `dice.gd`, `hero.gd`, `monster.gd`, `items.gd`, `dungeon.gd`, `party.gd`, `game.gd` |
| `scripts/world3d/` | `dungeon_view.gd` (3D level, fog of war, lights, actors, effects, markers, camera), `models.gd` (procedural heroes, monsters, props), `figure.gd` (rig + animation), `mats.gd` (materials), `portrait_studio.gd` (3D portraits for the UI) |
| `scripts/render/` | `world_animator.gd` (motion timing, effects, camera focus) and `particles.gd`, shared by the 3D view |
| `assets/textures/` | Tileable stone, brick, wood and rock (albedo + normal), made by `tools/make_textures.py` |
| `assets/fonts/` | Caladea and Inter (SIL Open Font License) |
| `scripts/art/` | The original 16x16 sprites (kept for reference) |
| `scripts/ui/game_ui.gd` | Party builder, side panel, sheets, level-up, inventory, menus |
| `scripts/audio/game_audio.gd` | Sound effects and music; files in `assets/audio/` |
| `scripts/session.gd` | Current game and save slots (`user://saves`) |
| `tests/` | Headless tests: `godot --headless --path godot --script res://tests/run_tests.gd`; `screenshot.gd` renders review screenshots (CI pushes them to the `ci-screenshots` branch) |

Saves use the same JSON format as the Android version. Premade heroes and parties live in
`scripts/core/party.gd` (`HEROES` and `PARTIES`); add entries there and they appear in the game.

The audio in `assets/audio/` was rendered from the Kotlin synthesiser
(`EXPORT_AUDIO_DIR=out ./gradlew -PcoreOnly=true :audio:test --tests com.rpg1.audio.ExportAudioTest`,
music then encoded to OGG with ffmpeg).
