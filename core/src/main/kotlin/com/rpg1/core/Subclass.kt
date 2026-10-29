package com.rpg1.core

/** Subclasses, chosen at 3rd level. Adapted from D&D lore to this game's tactical grid. */
enum class Subclass(val charClass: CharClass, val displayName: String, val lore: String) {
    PALADIN(CharClass.FIGHTER, "Paladin",
        "Bound by a sacred oath, a paladin fights as the sword of the gods: divine light burns in their " +
            "strikes and their touch mends the wounded."),
    KNIGHT(CharClass.FIGHTER, "Knight",
        "Sworn protectors who hold the line. A knight calls out the deadliest foe, shields their " +
            "companions and rallies them when the battle turns grim."),
    ASSASSIN(CharClass.ROGUE, "Assassin",
        "Killers who strike before the enemy knows the fight has begun, with poisoned steel and " +
            "a perfect sense for the moment of weakness."),
    RANGER(CharClass.ROGUE, "Ranger",
        "Scouts and hunters of the wild places beneath the world. A ranger marks their prey and " +
            "looses arrows with uncanny precision."),
    NECROMANCER(CharClass.WIZARD, "Necromancer",
        "Students of the boundary between life and death. Necromancers draw vitality from the " +
            "slain and raise the fallen to fight at their side."),
    MAGE(CharClass.WIZARD, "Mage",
        "Masters of raw arcane power. A mage wrings more force out of every spell and can pour " +
            "their very essence into a single devastating casting."),
    PRIEST(CharClass.CLERIC, "Priest",
        "Servants of the gods of life and mercy. A priest's healing flows stronger than any " +
            "other's, and their faith can pull allies back from death's door."),
    TEMPEST(CharClass.CLERIC, "Tempest",
        "Chosen of the storm gods. Thunder answers their prayers, and lightning lashes out at " +
            "anyone foolish enough to strike them.");

    val features: List<Feature> get() = Feature.entries.filter { it.subclass == this }

    companion object {
        fun forClass(cls: CharClass) = entries.filter { it.charClass == cls }
    }
}

enum class Feature(val subclass: Subclass, val level: Int, val title: String, val text: String) {
    DIVINE_SMITE(Subclass.PALADIN, 3, "Divine Smite",
        "Ready a smite: your next melee hit deals +2d8 radiant (+1d8 vs undead). 2 per long rest (3 at 7th)."),
    LAY_ON_HANDS(Subclass.PALADIN, 3, "Lay on Hands",
        "A pool of 5 x level HP. Action: touch an ally to heal them from the pool. Refills on a long rest."),
    AURA_OF_PROTECTION(Subclass.PALADIN, 7, "Aura of Protection",
        "Allies within 10 ft (and you) gain +1 AC, and add your CHA modifier (min +1) to death saves."),

    SHIELD_WALL(Subclass.KNIGHT, 3, "Shield Wall", "Trained in heavy defence: +1 AC."),
    CHALLENGE(Subclass.KNIGHT, 3, "Knightly Challenge",
        "Bonus action: challenge a foe. It has disadvantage attacking anyone but you, and turns its rage on you."),
    RALLYING_CRY(Subclass.KNIGHT, 3, "Rallying Cry",
        "When you use Second Wind, allies within 30 ft regain HP equal to your level."),
    WARDING_MANEUVER(Subclass.KNIGHT, 7, "Warding Maneuver",
        "Reaction: when an adjacent ally is hit, add 1d8 to their AC, possibly turning the hit into a miss."),

    ASSASSINATE(Subclass.ASSASSIN, 3, "Assassinate",
        "Advantage on attacks against foes that haven't acted yet this fight; hits on surprised foes are critical."),
    ENVENOM(Subclass.ASSASSIN, 3, "Envenom",
        "Bonus action: coat your blade. Your next hit deals +2d6 poison. 2 per short rest."),
    DEATH_STRIKE(Subclass.ASSASSIN, 7, "Death Strike",
        "When you hit a foe that hasn't acted yet, it makes a CON save or takes double damage."),

    ARCHERY(Subclass.RANGER, 3, "Ranger's Longbow",
        "You wield a longbow (1d8, 60 ft) and gain +2 to ranged attack rolls."),
    HUNTERS_MARK(Subclass.RANGER, 3, "Hunter's Mark",
        "Bonus action: mark a foe. Your weapon hits against it deal +1d6 damage."),
    VOLLEY(Subclass.RANGER, 7, "Volley",
        "Action: loose an arrow at every foe within 5 ft of a target you can see."),

    GRIM_HARVEST(Subclass.NECROMANCER, 3, "Grim Harvest",
        "When one of your spells of 1st level or higher kills a foe, you regain 2 x the spell's level in HP."),
    RAISE_DEAD(Subclass.NECROMANCER, 3, "Raise Dead",
        "Action + a spell slot: raise a corpse within 30 ft as a skeleton that fights for you. One at a time."),
    UNDEAD_THRALLS(Subclass.NECROMANCER, 7, "Undead Thralls",
        "Control two skeletons; they gain HP equal to your level and add your proficiency to damage."),

    ARCANE_RECOVERY(Subclass.MAGE, 3, "Arcane Recovery",
        "Once per long rest, a short rest also restores spell slots totalling half your level (rounded up)."),
    ARCANE_POTENCY(Subclass.MAGE, 3, "Arcane Potency",
        "Add your INT modifier to the damage of your damaging spells."),
    OVERCHANNEL(Subclass.MAGE, 7, "Overchannel",
        "Once per long rest: your next leveled damage spell deals maximum damage."),

    DISCIPLE_OF_LIFE(Subclass.PRIEST, 3, "Disciple of Life",
        "Healing spells restore an extra 2 + the spell's level. You always know Bless, and Revivify at 5th."),
    PRESERVE_LIFE(Subclass.PRIEST, 3, "Preserve Life",
        "Action: share 5 x level HP among wounded allies within 30 ft (each up to half their max). 1 per short rest."),
    BLESSED_HEALER(Subclass.PRIEST, 7, "Blessed Healer",
        "When you heal another creature with a spell, you regain 2 + the spell's level."),

    WRATH_OF_THE_STORM(Subclass.TEMPEST, 3, "Wrath of the Storm",
        "Reaction: when a foe hits you in melee, it makes a DEX save or takes 2d8 lightning (half on a save). WIS times per long rest."),
    DESTRUCTIVE_WRATH(Subclass.TEMPEST, 3, "Destructive Wrath",
        "Ready it: your next lightning or thunder damage roll is maximised. 1 per short rest. " +
            "You know Thunderwave, and Call Lightning at 5th."),
    DIVINE_STRIKE(Subclass.TEMPEST, 7, "Divine Strike",
        "Once per turn, your weapon hits deal +1d8 thunder."),
}

/** Limited-use subclass resources. */
enum class Resource(val shortRest: Boolean) {
    SMITE(false), LAY_ON_HANDS(false), ENVENOM(true), PRESERVE_LIFE(true),
    DESTRUCTIVE_WRATH(true), WRATH_OF_THE_STORM(false), OVERCHANNEL(false), ARCANE_RECOVERY(false),
    /** Fighter's Indomitable (not a subclass feature, but tracked the same way). */
    INDOMITABLE(false),
}
