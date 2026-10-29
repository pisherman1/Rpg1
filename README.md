# D20 Dungeon

A pixel-art dungeon crawler for Android, played with a party of four using D&D 5e-style rules.

## Getting the APK

Every push runs the **Build APK** GitHub Actions workflow. Open the latest run under
**Actions**, download the `d20-dungeon-apk` artifact, unzip it and install `app-debug.apk`
on your phone (you'll need to allow installing from unknown sources).

To build locally you need the Android SDK (API 35) and JDK 17:

```sh
./gradlew :app:assembleDebug                  # APK in app/build/outputs/apk/debug/
./gradlew -PcoreOnly=true :core:test          # rules engine only, no Android SDK needed
```

## Playing

1. **Assemble your party.** Each of the four slots can be built from scratch (tap the name
   to rename, use the arrows to pick race and class, *Reroll* for new 4d6-drop-lowest scores)
   or loaded from a premade (*Premade* per slot, or *Premade party* for all four).
2. **Explore.** Tap a square to walk there; the rest of the party follows the leader (★).
   Tap a portrait to change the leader. Walking onto chests, potions and gold picks them up.
3. **Fight.** Spotting a monster rolls Stealth (to surprise it) and initiative for everyone.
   On a hero's turn, the blue squares show where they can move. Tap an enemy to attack: melee
   if adjacent, otherwise rogues shoot, casters use their cantrip and fighters close in (or
   shoot when out of reach). Spell and weapon buttons arm a specific attack; then tap a target.
4. **Heal and rest.** Potions and Cure Wounds target a hero: arm them, then tap the hero or
   their portrait (in combat the target must be adjacent). Short rests spend hit dice; one long
   rest per depth restores everything, but resting may attract wandering monsters.
5. **Descend.** Find the stairs and tap *Descend*. Deeper levels have tougher monsters, and
   every 4th depth has an ogre guarding the stairs.

Tap the combat log to expand it; every roll is shown. The **i** button on a portrait opens
that hero's character sheet.

## Rules implemented

- Ability scores (4d6 drop lowest or fixed premade arrays), racial bonuses, modifiers,
  proficiency bonus by level, saving-throw proficiencies.
- d20 attack rolls vs AC, natural 20 crits (double dice), natural 1 misses, advantage /
  disadvantage (cancelling), ranged attacks at disadvantage with an enemy adjacent.
- Initiative, surprise (party's worst Stealth vs each monster's passive Perception),
  movement / action / bonus action per turn, Dash, Dodge, Disengage, opportunity attacks,
  moving through (but not stopping on) allies' squares, diagonal moves cost 5 ft.
- 0 HP: unconscious, death saves (nat 20 = back up, nat 1 = two failures), hits on a downed
  hero are failures (melee hits auto-crit), massive-damage instant death, stabilising.
- XP split between living heroes, levels 1–10 with the 5e XP table, HP and spell-slot growth.
- Races: Human, Dwarf, Elf, Halfling (Lucky rerolls natural 1s).
- Classes: Fighter (Second Wind, Action Surge), Rogue (Sneak Attack with advantage or an
  adjacent ally, Aim, Cunning Action), Wizard (Fire Bolt, Magic Missile, Burning Hands),
  Cleric (Sacred Flame, Guiding Bolt, Cure Wounds). Spells upcast with higher slots.
- Monsters from the SRD: giant rat, kobold, goblin, skeleton, zombie (Undead Fortitude),
  orc, gnoll, ghoul, bugbear, ogre. Encounters are sized from an XP budget per party level.

## Adding premade heroes and parties

Edit `core/src/main/kotlin/com/rpg1/core/Party.kt`, `object Premades`:

```kotlin
HeroTemplate("Name", Race.ELF, CharClass.ROGUE, listOf(10, 15, 14, 12, 13, 8)),
```

Scores are STR, DEX, CON, INT, WIS, CHA *before* racial bonuses; pass `null` (or leave the
list out) to roll them when the premade is picked. A `PartyTemplate` takes exactly four heroes.

## Project layout

- `core/` — pure Kotlin rules engine and pixel art (`art/Sprites.kt`, sprites as text), with
  unit tests including a bot that plays whole games.
- `app/` — the Android frontend: a single Canvas-based `GameView` and `MainActivity`.

Monster stat blocks are adapted from the D&D 5e System Reference Document 5.1 (CC BY 4.0).
