package com.rpg1.render

import java.nio.Buffer

/**
 * The slice of OpenGL ES 3.0 the renderer uses. Android implements it with GLES30;
 * a desktop implementation lets the exact same renderer run headless for previews.
 */
interface Gl {
    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int)
    fun getShaderCompiled(shader: Int): Boolean
    fun getShaderInfoLog(shader: Int): String
    fun deleteShader(shader: Int)
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun linkProgram(program: Int)
    fun getProgramLinked(program: Int): Boolean
    fun getProgramInfoLog(program: Int): String
    fun useProgram(program: Int)
    fun getUniformLocation(program: Int, name: String): Int
    fun uniform1i(location: Int, v: Int)
    fun uniform1f(location: Int, v: Float)
    fun uniform2f(location: Int, x: Float, y: Float)
    fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)

    fun genVertexArray(): Int
    fun bindVertexArray(vao: Int)
    fun genBuffer(): Int
    fun bindBuffer(target: Int, buffer: Int)
    fun bufferData(target: Int, sizeBytes: Int, data: Buffer?, usage: Int)
    fun bufferSubData(target: Int, offsetBytes: Int, sizeBytes: Int, data: Buffer)
    fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int)
    fun enableVertexAttribArray(index: Int)
    fun drawElements(mode: Int, count: Int, type: Int, offset: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)

    fun genTexture(): Int
    fun bindTexture(target: Int, texture: Int)
    fun activeTexture(unit: Int)
    fun texParameteri(target: Int, name: Int, value: Int)
    fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, data: Buffer?)
    fun pixelStorei(name: Int, value: Int)

    fun genFramebuffer(): Int
    fun bindFramebuffer(target: Int, framebuffer: Int)
    fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int)
    fun checkFramebufferStatus(target: Int): Int

    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear(mask: Int)
    fun enable(cap: Int)
    fun disable(cap: Int)
    fun blendFunc(src: Int, dst: Int)
    fun getError(): Int

    /** Constants share values between OpenGL ES 3.0 and desktop OpenGL. */
    companion object {
        const val VERTEX_SHADER = 0x8B31
        const val FRAGMENT_SHADER = 0x8B30
        const val ARRAY_BUFFER = 0x8892
        const val ELEMENT_ARRAY_BUFFER = 0x8893
        const val STATIC_DRAW = 0x88E4
        const val DYNAMIC_DRAW = 0x88E8
        const val FLOAT = 0x1406
        const val UNSIGNED_SHORT = 0x1403
        const val UNSIGNED_BYTE = 0x1401
        const val TRIANGLES = 0x0004
        const val TEXTURE_2D = 0x0DE1
        const val TEXTURE0 = 0x84C0
        const val TEXTURE_MIN_FILTER = 0x2801
        const val TEXTURE_MAG_FILTER = 0x2800
        const val TEXTURE_WRAP_S = 0x2802
        const val TEXTURE_WRAP_T = 0x2803
        const val NEAREST = 0x2600
        const val LINEAR = 0x2601
        const val CLAMP_TO_EDGE = 0x812F
        const val RGBA = 0x1908
        const val RGBA8 = 0x8058
        const val RG = 0x8227
        const val RG8 = 0x822B
        const val UNPACK_ALIGNMENT = 0x0CF5
        const val FRAMEBUFFER = 0x8D40
        const val COLOR_ATTACHMENT0 = 0x8CE0
        const val FRAMEBUFFER_COMPLETE = 0x8CD5
        const val COLOR_BUFFER_BIT = 0x4000
        const val BLEND = 0x0BE2
        const val DEPTH_TEST = 0x0B71
        const val SCISSOR_TEST = 0x0C11
        const val ZERO = 0
        const val ONE = 1
        const val SRC_ALPHA = 0x0302
        const val ONE_MINUS_SRC_ALPHA = 0x0303
    }
}
