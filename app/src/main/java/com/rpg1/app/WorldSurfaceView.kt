package com.rpg1.app

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import com.rpg1.render.WorldFrame
import com.rpg1.render.WorldRenderer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 view that draws the dungeon world. The UI thread hands over finished
 * [WorldFrame] snapshots; this view renders the latest one on its GL thread.
 */
@SuppressLint("ViewConstructor")
class WorldSurfaceView(context: Context, private val onFailure: (Throwable) -> Unit) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private val latest = AtomicReference<WorldFrame?>(null)
    private var renderer: WorldRenderer? = null
    private var surfaceW = 1
    private var surfaceH = 1
    @Volatile var failed = false
        private set

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun submit(frame: WorldFrame?) {
        if (failed) return
        latest.set(frame)
        requestRender()
    }

    private fun fail(t: Throwable) {
        Log.e("D20", "World renderer failed; falling back to Canvas", t)
        failed = true
        renderer = null
        post { onFailure(t) }
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        try {
            renderer = WorldRenderer(AndroidGl(), Art.atlas).also { it.init() }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
    }

    override fun onDrawFrame(unused: GL10?) {
        val r = renderer ?: return
        try {
            r.clear(surfaceW, surfaceH)
            latest.get()?.let { r.render(it) }
        } catch (t: Throwable) {
            fail(t)
        }
    }
}
