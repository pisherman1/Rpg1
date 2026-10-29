package com.rpg1.render

/**
 * An immutable snapshot of everything the world renderer draws in one frame.
 * Built on the UI thread by [WorldAnimator], consumed on the GL thread.
 */
class WorldFrame(
    val viewW: Int,
    val viewH: Int,
    /** Screen pixels per world pixel. */
    val scale: Int,
    /** Top-left of the view in world pixels (16 per tile). */
    val camLeft: Float,
    val camTop: Float,
    /** [SPRITE_STRIDE] floats per sprite, drawn in order and lit. */
    val sprites: FloatArray,
    val spriteCount: Int,
    /** Same layout, drawn additively after lighting so they glow. */
    val emissive: FloatArray,
    val emissiveCount: Int,
    /** [LIGHT_STRIDE] floats per light. */
    val lights: FloatArray,
    val lightCount: Int,
    /** Two bytes per dungeon square: visible, explored. */
    val vis: ByteArray,
    val visW: Int,
    val visH: Int,
    val ambient: FloatArray,
) {
    companion object {
        /** id, x, y, w, h, r, g, b, a, flash, rotate(0/1) */
        const val SPRITE_STRIDE = 11
        /** x, y, radius, r, g, b, intensity */
        const val LIGHT_STRIDE = 7
    }
}

/** Growable float list used to assemble frames without per-item allocation. */
class FloatList(capacity: Int = 1024) {
    var data = FloatArray(capacity)
        private set
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
