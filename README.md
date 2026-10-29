# Dungeon Delver

A pixel-art dungeon crawler for Android, played with a party of four using D&D 5e-style rules.

## Getting the APK

Every push runs the **Build APK** GitHub Actions workflow. Open the latest run under
**Actions**, download the `dungeon-delver-apk` artifact, unzip it and install `app-debug.apk`
on your phone (you'll need to allow installing from unknown sources).
Builds are signed with the debug key in `app/debug.keystore`, so each new build installs over
the previous one and keeps your saves.

To build locally you need the Android SDK (API 35) and JDK 17:

```sh
./gradlew :app:assembleDebug                  # APK in app/build/outputs/apk/debug/
./gradlew -PcoreOnly=true :core:test :render:test :audio:test   # no Android SDK needed
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
5. **Level up.** XP from every kill is split between living heroes. When a hero has choices
   to make, a gold **▲** appears on their portrait: tap it to spend ability score points
   (levels 4 and 8, plus 6 for fighters) and learn new spells (casters, every level).
6. **Gear up.** Treasure from chests, bosses and the occasional monster goes into the party
   **Bag** (top right of the map). Open it outside combat to equip items on any hero.
7. **Descend.** Find the stairs and tap *Descend*. Deeper levels have tougher monsters, and
   every 4th depth has an ogre guarding the stairs.

**Saving.** The game autosaves when you leave the app, start a new depth, or finish a fight;
*Continue* on the title screen picks up from there. *Menu* (top right of the map) has three
manual save slots plus *Save & Quit to Title*, and *Load Game* on the title screen loads any
slot. Saves capture everything, even mid-combat. When the whole party falls, the autosave
is deleted, but manual saves are kept.

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
- XP split between living heroes; levels 1–10 on a gentler curve than 5e (80 / 250 / 550 / 1000 /
  1600 / 2350 / 3250 / 4300 / 5500 XP), about a level per dungeon floor. Every level brings something
  new (features, spells, slots, Sneak Attack dice, ability score improvements), celebrated with a
  banner; the character sheet previews the next level.
- Casters start with one cantrip and one spell (Wizard: Fire Bolt, Magic Missile; Cleric: Sacred
  Flame, Cure Wounds) and learn one more every level.
- Races: Human, Dwarf, Elf, Halfling (Lucky rerolls natural 1s).
- Classes: Fighter (Second Wind, Action Surge, Extra Attack at 5th, Indomitable at 9th, a third
  attack at 10th), Rogue (Sneak Attack with advantage or an adjacent ally, Aim, Cunning Action,
  Uncanny Dodge at 5th, Evasive at 6th), Wizard and Cleric.
- Spells (a new one learned every level, gated by slot level; higher slots upcast them):
  - Wizard: Fire Bolt, Ray of Frost, Magic Missile, Burning Hands, Thunderwave, Sleep, Scorching
    Ray, Shatter, Acid Arrow, Fireball, Lightning Bolt, Ice Storm, Cone of Cold.
  - Cleric: Sacred Flame, Toll the Dead, Guiding Bolt, Cure Wounds, Healing Word (bonus action),
    Inflict Wounds, Bless, Prayer of Healing, Spirit Guardians, Mass Healing Word, Revivify (300
    gold, brings back a fallen hero), Flame Strike, Mass Cure Wounds.
- Subclasses at 3rd level (adapted from D&D lore to the tactical grid):
  - Fighter: **Paladin** (Divine Smite, Lay on Hands, Aura of Protection) or **Knight**
    (Shield Wall, Knightly Challenge, Rallying Cry, Warding Maneuver).
  - Rogue: **Assassin** (Assassinate, Envenom, Death Strike) or **Ranger** (longbow and
    Archery, Hunter's Mark, Volley).
  - Wizard: **Necromancer** (Grim Harvest, Raise Dead skeleton minions, Undead Thralls) or
    **Mage** (Arcane Recovery, Arcane Potency, Overchannel).
  - Cleric: **Priest** (Disciple of Life, Preserve Life, Blessed Healer, Bless/Revivify) or
    **Tempest** (Wrath of the Storm, Destructive Wrath, Thunderwave/Call Lightning, Divine Strike).
- Ability score improvements (+2 / +1+1, max 20); CON raises max HP for every level.
- Magic items (SRD): +1/+2/+3 weapons, armor and Wands of the War Mage, Ring and Cloak of
  Protection, Amulet of Health, Gauntlets of Ogre Power, Headband of Intellect, Periapt of
  Wound Closure, Boots of Striding. Rarity improves with depth; ogres always drop treasure.
- Monsters from the SRD: giant rat, kobold, goblin, skeleton, zombie (Undead Fortitude),
  orc, gnoll, ghoul, bugbear, ogre. Encounters are sized from an XP budget per party level.

## Adding premade heroes and parties

Edit `core/src/main/kotlin/com/rpg1/core/Party.kt`, `object Premades`:

```kotlin
HeroTemplate("Name", Race.ELF, CharClass.ROGUE, listOf(10, 15, 14, 12, 13, 8)),
```

Scores are STR, DEX, CON, INT, WIS, CHA *before* racial bonuses; pass `null` (or leave the
list out) to roll them when the premade is picked. A `PartyTemplate` takes exactly four heroes.

## Graphics

The dungeon is drawn with OpenGL ES 3.0 (required; every Android device since ~2014 has it):

1. sprites render into an offscreen buffer at true pixel resolution (16 px per square);
2. a light map gathers flickering torches carried by each hero, wall torches, stairs and spell
   glows, with stepped falloff for a pixel-art look;
3. the light map is applied with fog of war: what the party can see is lit, remembered areas
   fade to a cold blue memory, with soft edges from a per-square visibility texture;
4. projectiles and particles are drawn on top and glow, then bright areas are blurred into bloom;
5. the image is scaled up pixel-perfectly with sub-pixel camera scrolling, colour grading and
   a vignette.

Creatures glide between squares, bob when idle, lunge when attacking, flash when hit and fade
out when slain; crits shake the screen. Text and UI are drawn on top with Canvas so they stay
crisp. If OpenGL fails on a device, the game falls back to plain Canvas drawing.

## Sound and music

All audio is synthesised by the game itself (no sound files): a small chiptune synthesizer with
square, pulse, triangle and noise channels, envelopes, pitch slides and echo renders ~30 sound
effects and four music tracks (title, exploration, combat and defeat) on first launch and caches
them. Effects are timed to the animations (a spell's cast and its impact), music crossfades
between exploring and combat, and both can be turned off in the Menu.

## Project layout

- `core/` — pure Kotlin rules engine and pixel art (`art/Sprites.kt`, sprites as text), with
  unit tests including a bot that plays whole games.
- `render/` — pure Kotlin world renderer: animation, particles and lighting (`WorldAnimator`),
  and the OpenGL pipeline (`WorldRenderer`) behind a small `Gl` interface, so it can also run
  on desktop OpenGL.
- `audio/` — pure Kotlin synthesizer (`Synth`), sound-effect designs (`Sfx`) and the music
  sequencer with its compositions (`Music`).
- `app/` — the Android frontend: `WorldSurfaceView` (GL), `GameView` (Canvas UI) and
  `MainActivity`.

Monster stat blocks are adapted from the D&D 5e System Reference Document 5.1 (CC BY 4.0).
