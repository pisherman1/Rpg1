# Dungeon Delver 2 (Godot)

A Godot 4.5 port of Dungeon Delver: the same D&D 5e-style rules, party of four, subclasses,
loot, saves, pixel art, lighting and chiptune audio as the Android/Kotlin version in the parent folder.

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
| `scripts/art/` | `sprites.gd` (16x16 pixel art as text) and `atlas.gd` (builds textures, composes heroes by race/class/gender) |
| `scripts/render/` | `world_animator.gd` (motion, effects, camera), `particles.gd`, `world_view.gd` (render passes) |
| `shaders/` | `lit.gdshader` (light map + fog of war), `final.gdshader` (bloom, grading, vignette) |
| `scripts/ui/game_ui.gd` | Party builder, side panel, sheets, level-up, inventory, menus |
| `scripts/audio/game_audio.gd` | Sound effects and music; files in `assets/audio/` |
| `scripts/session.gd` | Current game and save slots (`user://saves`) |
| `tests/` | Headless tests: `godot --headless --path godot --script res://tests/run_tests.gd` |

Saves use the same JSON format as the Android version. Premade heroes and parties live in
`scripts/core/party.gd` (`HEROES` and `PARTIES`); add entries there and they appear in the game.

The audio in `assets/audio/` was rendered from the Kotlin synthesiser
(`EXPORT_AUDIO_DIR=out ./gradlew -PcoreOnly=true :audio:test --tests com.rpg1.audio.ExportAudioTest`,
music then encoded to OGG with ffmpeg).
