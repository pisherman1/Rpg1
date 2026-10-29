package com.rpg1.render

import com.rpg1.render.Gl.Companion as GL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws a [WorldFrame] with OpenGL ES 3.0:
 *  1. sprites into a pixel-resolution scene buffer,
 *  2. lights into a light map,
 *  3. scene x light with fog of war (soft edges from a per-square visibility texture), then glowing sprites,
 *  4. bright-pass and blur for bloom,
 *  5. pixel-perfect upscale with sub-pixel camera offset, bloom, colour grading and vignette.
 * Must be created and used on the GL thread.
 */
class WorldRenderer(private val gl: Gl, private val atlas: Atlas) {
    private class Program(val id: Int, private val gl: Gl) {
        private val locations = HashMap<String, Int>()
        fun loc(name: String) = locations.getOrPut(name) { gl.getUniformLocation(id, name) }
    }

    private class Target(var fbo: Int = 0, var tex: Int = 0, var w: Int = 0, var h: Int = 0)

    private lateinit var spriteProgram: Program
    private lateinit var lightProgram: Program
    private lateinit var litProgram: Program
    private lateinit var brightProgram: Program
    private lateinit var blurProgram: Program
    private lateinit var finalProgram: Program

    private var atlasTex = 0
    private var visTex = 0
    private var visW = 0
    private var visH = 0
    private var quadVao = 0
    private var quadVbo = 0
    private var emptyVao = 0

    private val scene = Target()
    private val light = Target()
    private val lit = Target()
    private val bloomA = Target()
    private val bloomB = Target()

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(MAX_QUADS * 4 * FLOATS_PER_VERTEX * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var quadCount = 0

    /** (Re)creates all GL objects; call when the surface (and its context) is created. */
    fun init() {
        spriteProgram = program(Shaders.QUAD_VERT, Shaders.SPRITE_FRAG)
        lightProgram = program(Shaders.QUAD_VERT, Shaders.LIGHT_FRAG)
        litProgram = program(Shaders.FULLSCREEN_VERT, Shaders.LIT_FRAG)
        brightProgram = program(Shaders.FULLSCREEN_VERT, Shaders.BRIGHT_FRAG)
        blurProgram = program(Shaders.FULLSCREEN_VERT, Shaders.BLUR_FRAG)
        finalProgram = program(Shaders.FULLSCREEN_VERT, Shaders.FINAL_FRAG)

        atlasTex = texture(GL.NEAREST)
        val bytes = atlas.rgbaBytes()
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buf.flip()
        gl.pixelStorei(GL.UNPACK_ALIGNMENT, 1)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.RGBA8, atlas.width, atlas.height, GL.RGBA, GL.UNSIGNED_BYTE, buf)

        visTex = texture(GL.LINEAR)
        visW = 0
        visH = 0

        quadVao = gl.genVertexArray()
        gl.bindVertexArray(quadVao)
        quadVbo = gl.genBuffer()
        gl.bindBuffer(GL.ARRAY_BUFFER, quadVbo)
        gl.bufferData(GL.ARRAY_BUFFER, vertices.capacity() * 4, null, GL.DYNAMIC_DRAW)
        val stride = FLOATS_PER_VERTEX * 4
        gl.vertexAttribPointer(0, 2, GL.FLOAT, false, stride, 0)
        gl.vertexAttribPointer(1, 2, GL.FLOAT, false, stride, 8)
        gl.vertexAttribPointer(2, 4, GL.FLOAT, false, stride, 16)
        gl.vertexAttribPointer(3, 1, GL.FLOAT, false, stride, 32)
        for (i in 0..3) gl.enableVertexAttribArray(i)
        val indices = ByteBuffer.allocateDirect(MAX_QUADS * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (q in 0 until MAX_QUADS) {
            val v = q * 4
            indices.put(v.toShort()).put((v + 1).toShort()).put((v + 2).toShort())
            indices.put(v.toShort()).put((v + 2).toShort()).put((v + 3).toShort())
        }
        indices.flip()
        gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, gl.genBuffer())
        gl.bufferData(GL.ELEMENT_ARRAY_BUFFER, MAX_QUADS * 6 * 2, indices, GL.STATIC_DRAW)
        gl.bindVertexArray(0)
        emptyVao = gl.genVertexArray()

        for (t in listOf(scene, light, lit, bloomA, bloomB)) { t.fbo = 0; t.w = 0; t.h = 0 }
        gl.disable(GL.DEPTH_TEST)
    }

    private fun program(vs: String, fs: String): Program {
        fun compile(type: Int, src: String): Int {
            val s = gl.createShader(type)
            gl.shaderSource(s, src)
            gl.compileShader(s)
            if (!gl.getShaderCompiled(s)) throw IllegalStateException("Shader compile failed: ${gl.getShaderInfoLog(s)}\n$src")
            return s
        }
        val p = gl.createProgram()
        val v = compile(GL.VERTEX_SHADER, vs)
        val f = compile(GL.FRAGMENT_SHADER, fs)
        gl.attachShader(p, v)
        gl.attachShader(p, f)
        gl.linkProgram(p)
        if (!gl.getProgramLinked(p)) throw IllegalStateException("Program link failed: ${gl.getProgramInfoLog(p)}")
        gl.deleteShader(v)
        gl.deleteShader(f)
        return Program(p, gl)
    }

    private fun texture(filter: Int): Int {
        val t = gl.genTexture()
        gl.bindTexture(GL.TEXTURE_2D, t)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, filter)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, filter)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
        return t
    }

    private fun ensure(t: Target, w: Int, h: Int, filter: Int) {
        if (t.fbo != 0 && t.w == w && t.h == h) return
        if (t.fbo == 0) {
            t.tex = texture(filter)
            t.fbo = gl.genFramebuffer()
        }
        gl.bindTexture(GL.TEXTURE_2D, t.tex)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.RGBA8, w, h, GL.RGBA, GL.UNSIGNED_BYTE, null)
        gl.bindFramebuffer(GL.FRAMEBUFFER, t.fbo)
        gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, t.tex, 0)
        check(gl.checkFramebufferStatus(GL.FRAMEBUFFER) == GL.FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete" }
        t.w = w
        t.h = h
    }

    private fun bindTarget(t: Target?) {
        gl.bindFramebuffer(GL.FRAMEBUFFER, t?.fbo ?: 0)
        if (t != null) gl.viewport(0, 0, t.w, t.h)
    }

    private fun bindTex(unit: Int, tex: Int) {
        gl.activeTexture(GL.TEXTURE0 + unit)
        gl.bindTexture(GL.TEXTURE_2D, tex)
    }

    private fun fullscreen() {
        gl.bindVertexArray(emptyVao)
        gl.drawArrays(GL.TRIANGLES, 0, 3)
    }

    // --- Quad batching -------------------------------------------------------

    private fun vertex(x: Float, y: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float, flash: Float) {
        vertices.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a).put(flash)
    }

    private fun flush() {
        if (quadCount == 0) return
        vertices.flip()
        gl.bindVertexArray(quadVao)
        gl.bindBuffer(GL.ARRAY_BUFFER, quadVbo)
        gl.bufferSubData(GL.ARRAY_BUFFER, 0, vertices.limit() * 4, vertices)
        gl.drawElements(GL.TRIANGLES, quadCount * 6, GL.UNSIGNED_SHORT, 0)
        vertices.clear()
        quadCount = 0
    }

    private fun quad(x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float,
                     r: Float, g: Float, b: Float, a: Float, flash: Float, rotate: Boolean) {
        if (quadCount == MAX_QUADS) flush()
        if (rotate) {
            // Lying down: rotate the sprite 90 degrees clockwise.
            vertex(x, y, u0, v1, r, g, b, a, flash)
            vertex(x + w, y, u0, v0, r, g, b, a, flash)
            vertex(x + w, y + h, u1, v0, r, g, b, a, flash)
            vertex(x, y + h, u1, v1, r, g, b, a, flash)
        } else {
            vertex(x, y, u0, v0, r, g, b, a, flash)
            vertex(x + w, y, u1, v0, r, g, b, a, flash)
            vertex(x + w, y + h, u1, v1, r, g, b, a, flash)
            vertex(x, y + h, u0, v1, r, g, b, a, flash)
        }
        quadCount++
    }

    private fun drawSprites(data: FloatArray, count: Int) {
        val s = WorldFrame.SPRITE_STRIDE
        for (i in 0 until count) {
            val o = i * s
            val reg = atlas.region(data[o].toInt())
            quad(data[o + 1], data[o + 2], data[o + 3], data[o + 4], reg[0], reg[1], reg[2], reg[3],
                data[o + 5], data[o + 6], data[o + 7], data[o + 8], data[o + 9], data[o + 10] > 0.5f)
        }
        flush()
    }

    // --- Frame -----------------------------------------------------------------

    /** Renders [f] into the default framebuffer's (0, 0, viewW, viewH) viewport. */
    fun render(f: WorldFrame) {
        val sw = ceil(f.viewW / f.scale.toFloat()).toInt() + 2
        val sh = ceil(f.viewH / f.scale.toFloat()).toInt() + 2
        val ox = floor(f.camLeft) - 1f
        val oy = floor(f.camTop) - 1f
        ensure(scene, sw, sh, GL.NEAREST)
        ensure(light, sw, sh, GL.NEAREST)
        ensure(lit, sw, sh, GL.NEAREST)
        ensure(bloomA, maxOf(1, sw / 2), maxOf(1, sh / 2), GL.LINEAR)
        ensure(bloomB, maxOf(1, sw / 2), maxOf(1, sh / 2), GL.LINEAR)

        // 1. Scene
        bindTarget(scene)
        gl.clearColor(0f, 0f, 0f, 0f)
        gl.clear(GL.COLOR_BUFFER_BIT)
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.SRC_ALPHA, GL.ONE_MINUS_SRC_ALPHA)
        gl.useProgram(spriteProgram.id)
        gl.uniform4f(spriteProgram.loc("uView"), ox, oy, sw.toFloat(), sh.toFloat())
        gl.uniform1i(spriteProgram.loc("uTex"), 0)
        bindTex(0, atlasTex)
        drawSprites(f.sprites, f.spriteCount)

        // 2. Light map (stored at half intensity)
        bindTarget(light)
        gl.clearColor(f.ambient[0] * 0.5f, f.ambient[1] * 0.5f, f.ambient[2] * 0.5f, 1f)
        gl.clear(GL.COLOR_BUFFER_BIT)
        gl.blendFunc(GL.ONE, GL.ONE)
        gl.useProgram(lightProgram.id)
        gl.uniform4f(lightProgram.loc("uView"), ox, oy, sw.toFloat(), sh.toFloat())
        gl.uniform1f(lightProgram.loc("uBands"), LIGHT_BANDS)
        val ls = WorldFrame.LIGHT_STRIDE
        for (i in 0 until f.lightCount) {
            val o = i * ls
            val x = f.lights[o]
            val y = f.lights[o + 1]
            val r = f.lights[o + 2]
            quad(x - r, y - r, r * 2, r * 2, -1f, -1f, 1f, 1f,
                f.lights[o + 3], f.lights[o + 4], f.lights[o + 5], f.lights[o + 6], 0f, false)
        }
        flush()

        // Fog of war texture
        uploadVis(f)

        // 3. Lit scene, then glowing sprites on top
        bindTarget(lit)
        gl.disable(GL.BLEND)
        gl.useProgram(litProgram.id)
        gl.uniform1i(litProgram.loc("uScene"), 0)
        gl.uniform1i(litProgram.loc("uLight"), 1)
        gl.uniform1i(litProgram.loc("uVis"), 2)
        gl.uniform4f(litProgram.loc("uRect"), ox, oy, sw.toFloat(), sh.toFloat())
        gl.uniform2f(litProgram.loc("uMapPx"), f.visW * 16f, f.visH * 16f)
        bindTex(0, scene.tex)
        bindTex(1, light.tex)
        bindTex(2, visTex)
        fullscreen()
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.SRC_ALPHA, GL.ONE)
        gl.useProgram(spriteProgram.id)
        gl.uniform4f(spriteProgram.loc("uView"), ox, oy, sw.toFloat(), sh.toFloat())
        bindTex(0, atlasTex)
        drawSprites(f.emissive, f.emissiveCount)
        gl.disable(GL.BLEND)

        // 4. Bloom
        bindTarget(bloomA)
        gl.useProgram(brightProgram.id)
        gl.uniform1i(brightProgram.loc("uTex"), 0)
        gl.uniform1f(brightProgram.loc("uThreshold"), BLOOM_THRESHOLD)
        bindTex(0, lit.tex)
        fullscreen()
        gl.useProgram(blurProgram.id)
        gl.uniform1i(blurProgram.loc("uTex"), 0)
        repeat(2) {
            bindTarget(bloomB)
            gl.uniform2f(blurProgram.loc("uStep"), 1f / bloomA.w, 0f)
            bindTex(0, bloomA.tex)
            fullscreen()
            bindTarget(bloomA)
            gl.uniform2f(blurProgram.loc("uStep"), 0f, 1f / bloomA.h)
            bindTex(0, bloomB.tex)
            fullscreen()
        }

        // 5. Final composite to the screen
        bindTarget(null)
        gl.viewport(0, 0, f.viewW, f.viewH)
        gl.useProgram(finalProgram.id)
        gl.uniform1i(finalProgram.loc("uLit"), 0)
        gl.uniform1i(finalProgram.loc("uBloom"), 1)
        gl.uniform4f(finalProgram.loc("uRect"), ox, oy, sw.toFloat(), sh.toFloat())
        gl.uniform4f(finalProgram.loc("uCam"), f.camLeft, f.camTop, f.scale.toFloat(), f.viewH.toFloat())
        gl.uniform2f(finalProgram.loc("uViewSize"), f.viewW.toFloat(), f.viewH.toFloat())
        gl.uniform1f(finalProgram.loc("uBloomStrength"), BLOOM_STRENGTH)
        bindTex(0, lit.tex)
        bindTex(1, bloomA.tex)
        fullscreen()
        bindTex(0, 0)
    }

    private var visBuffer: ByteBuffer? = null

    private fun uploadVis(f: WorldFrame) {
        val size = f.vis.size
        val buf = visBuffer?.takeIf { it.capacity() >= size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { visBuffer = it }
        buf.clear()
        buf.put(f.vis)
        buf.flip()
        gl.bindTexture(GL.TEXTURE_2D, visTex)
        gl.pixelStorei(GL.UNPACK_ALIGNMENT, 1)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.RG8, f.visW, f.visH, GL.RG, GL.UNSIGNED_BYTE, buf)
        visW = f.visW
        visH = f.visH
    }

    /** Clears the screen when there is nothing to draw. */
    fun clear(width: Int, height: Int) {
        bindTarget(null)
        gl.viewport(0, 0, width, height)
        gl.clearColor(0f, 0f, 0f, 1f)
        gl.clear(GL.COLOR_BUFFER_BIT)
    }

    companion object {
        const val MAX_QUADS = 8192
        const val FLOATS_PER_VERTEX = 9
        const val LIGHT_BANDS = 9f
        const val BLOOM_THRESHOLD = 0.72f
        const val BLOOM_STRENGTH = 0.85f
    }
}
