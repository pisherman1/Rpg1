package com.rpg1.app

import android.opengl.GLES30 as G
import com.rpg1.render.Gl
import java.nio.Buffer

/** The renderer's GL interface backed by Android's OpenGL ES 3.0. */
class AndroidGl : Gl {
    private val one = IntArray(1)

    private inline fun gen(block: (IntArray) -> Unit): Int {
        block(one)
        return one[0]
    }

    override fun createShader(type: Int) = G.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = G.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = G.glCompileShader(shader)
    override fun getShaderCompiled(shader: Int): Boolean {
        G.glGetShaderiv(shader, G.GL_COMPILE_STATUS, one, 0)
        return one[0] == G.GL_TRUE
    }
    override fun getShaderInfoLog(shader: Int): String = G.glGetShaderInfoLog(shader)
    override fun deleteShader(shader: Int) = G.glDeleteShader(shader)
    override fun createProgram() = G.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = G.glAttachShader(program, shader)
    override fun linkProgram(program: Int) = G.glLinkProgram(program)
    override fun getProgramLinked(program: Int): Boolean {
        G.glGetProgramiv(program, G.GL_LINK_STATUS, one, 0)
        return one[0] == G.GL_TRUE
    }
    override fun getProgramInfoLog(program: Int): String = G.glGetProgramInfoLog(program)
    override fun useProgram(program: Int) = G.glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String) = G.glGetUniformLocation(program, name)
    override fun uniform1i(location: Int, v: Int) = G.glUniform1i(location, v)
    override fun uniform1f(location: Int, v: Float) = G.glUniform1f(location, v)
    override fun uniform2f(location: Int, x: Float, y: Float) = G.glUniform2f(location, x, y)
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = G.glUniform4f(location, x, y, z, w)

    override fun genVertexArray() = gen { G.glGenVertexArrays(1, it, 0) }
    override fun bindVertexArray(vao: Int) = G.glBindVertexArray(vao)
    override fun genBuffer() = gen { G.glGenBuffers(1, it, 0) }
    override fun bindBuffer(target: Int, buffer: Int) = G.glBindBuffer(target, buffer)
    override fun bufferData(target: Int, sizeBytes: Int, data: Buffer?, usage: Int) = G.glBufferData(target, sizeBytes, data, usage)
    override fun bufferSubData(target: Int, offsetBytes: Int, sizeBytes: Int, data: Buffer) =
        G.glBufferSubData(target, offsetBytes, sizeBytes, data)
    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) =
        G.glVertexAttribPointer(index, size, type, normalized, stride, offset)
    override fun enableVertexAttribArray(index: Int) = G.glEnableVertexAttribArray(index)
    override fun drawElements(mode: Int, count: Int, type: Int, offset: Int) = G.glDrawElements(mode, count, type, offset)
    override fun drawArrays(mode: Int, first: Int, count: Int) = G.glDrawArrays(mode, first, count)

    override fun genTexture() = gen { G.glGenTextures(1, it, 0) }
    override fun bindTexture(target: Int, texture: Int) = G.glBindTexture(target, texture)
    override fun activeTexture(unit: Int) = G.glActiveTexture(unit)
    override fun texParameteri(target: Int, name: Int, value: Int) = G.glTexParameteri(target, name, value)
    override fun texImage2D(target: Int, level: Int, internalFormat: Int, width: Int, height: Int, format: Int, type: Int, data: Buffer?) =
        G.glTexImage2D(target, level, internalFormat, width, height, 0, format, type, data)
    override fun pixelStorei(name: Int, value: Int) = G.glPixelStorei(name, value)

    override fun genFramebuffer() = gen { G.glGenFramebuffers(1, it, 0) }
    override fun bindFramebuffer(target: Int, framebuffer: Int) = G.glBindFramebuffer(target, framebuffer)
    override fun framebufferTexture2D(target: Int, attachment: Int, texTarget: Int, texture: Int, level: Int) =
        G.glFramebufferTexture2D(target, attachment, texTarget, texture, level)
    override fun checkFramebufferStatus(target: Int) = G.glCheckFramebufferStatus(target)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = G.glViewport(x, y, width, height)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = G.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = G.glClear(mask)
    override fun enable(cap: Int) = G.glEnable(cap)
    override fun disable(cap: Int) = G.glDisable(cap)
    override fun blendFunc(src: Int, dst: Int) = G.glBlendFunc(src, dst)
    override fun getError() = G.glGetError()
}
