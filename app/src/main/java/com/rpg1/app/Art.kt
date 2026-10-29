package com.rpg1.app

import com.rpg1.render.Atlas

/** The sprite atlas, built once and shared by the GL renderer and the animator. */
object Art {
    val atlas: Atlas by lazy { Atlas.build() }
}
