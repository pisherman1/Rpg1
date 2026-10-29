package com.rpg1.render

/** GLSL ES 3.00 sources for the world pipeline. */
object Shaders {
    /** Sprites and light quads: world-pixel positions mapped into the current target. */
    const val QUAD_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
layout(location = 3) in float aFlash;
uniform vec4 uView; // origin x, origin y, width, height (world pixels)
out vec2 vUv;
out vec4 vColor;
out float vFlash;
void main() {
    vec2 p = (aPos - uView.xy) / uView.zw;
    gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);
    vUv = aUv;
    vColor = aColor;
    vFlash = aFlash;
}
"""

    const val SPRITE_FRAG = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv;
in vec4 vColor;
in float vFlash;
out vec4 outColor;
void main() {
    vec4 t = texture(uTex, vUv);
    if (t.a < 0.01) discard;
    vec3 c = mix(t.rgb * vColor.rgb, vec3(1.0), vFlash);
    outColor = vec4(c, t.a * vColor.a);
}
"""

    /** Radial light; vUv runs -1..1 across the quad. Stored at half intensity so 2x fits in RGBA8. */
    const val LIGHT_FRAG = """#version 300 es
precision mediump float;
uniform float uBands;
in vec2 vUv;
in vec4 vColor;
in float vFlash;
out vec4 outColor;
void main() {
    float d = length(vUv);
    float f = clamp(1.0 - d, 0.0, 1.0);
    f = f * f * (3.0 - 2.0 * f);
    // Quantised falloff gives the stepped light rings of pixel-art lighting.
    f = floor(f * uBands + 0.5) / uBands;
    outColor = vec4(vColor.rgb * f * vColor.a * 0.5, 1.0);
}
"""

    /** Fullscreen triangle from gl_VertexID; no vertex buffer needed. */
    const val FULLSCREEN_VERT = """#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /** Applies the light map and fog of war: unseen-but-explored areas become a cold memory. */
    const val LIT_FRAG = """#version 300 es
precision mediump float;
uniform sampler2D uScene;
uniform sampler2D uLight;
uniform sampler2D uVis;
uniform vec4 uRect;   // scene origin x, y, width, height (world pixels)
uniform vec2 uMapPx;  // dungeon size in world pixels
in vec2 vUv;
out vec4 outColor;
void main() {
    vec3 scene = texture(uScene, vUv).rgb;
    vec3 light = texture(uLight, vUv).rgb * 2.0;
    vec2 world = vec2(uRect.x + vUv.x * uRect.z, uRect.y + (1.0 - vUv.y) * uRect.w);
    vec2 vis = texture(uVis, world / uMapPx).rg;
    float seen = smoothstep(0.1, 0.6, vis.r);
    float known = smoothstep(0.2, 0.8, vis.g);
    float lum = dot(scene, vec3(0.299, 0.587, 0.114));
    vec3 memory = mix(vec3(lum), scene, 0.25) * vec3(0.30, 0.34, 0.50);
    vec3 lit = scene * light;
    outColor = vec4(mix(memory * known, lit, seen), 1.0);
}
"""

    const val BRIGHT_FRAG = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform float uThreshold;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec3 c = texture(uTex, vUv).rgb;
    float l = max(c.r, max(c.g, c.b));
    float k = max(l - uThreshold, 0.0) / max(l, 0.0001);
    outColor = vec4(c * k, 1.0);
}
"""

    const val BLUR_FRAG = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uStep;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec3 c = texture(uTex, vUv).rgb * 0.227027;
    c += texture(uTex, vUv + uStep * 1.3846154).rgb * 0.3162162;
    c += texture(uTex, vUv - uStep * 1.3846154).rgb * 0.3162162;
    c += texture(uTex, vUv + uStep * 3.2307692).rgb * 0.0702703;
    c += texture(uTex, vUv - uStep * 3.2307692).rgb * 0.0702703;
    outColor = vec4(c, 1.0);
}
"""

    /** Upscales the pixel-resolution image with sub-pixel camera offset, adds bloom, grades the colour. */
    const val FINAL_FRAG = """#version 300 es
precision highp float;
uniform sampler2D uLit;
uniform sampler2D uBloom;
uniform vec4 uRect;      // scene origin x, y, width, height (world pixels)
uniform vec4 uCam;       // camera left, top (world pixels), scale, viewport height (screen pixels)
uniform vec2 uViewSize;  // viewport size (screen pixels)
uniform float uBloomStrength;
out vec4 outColor;
void main() {
    vec2 frag = gl_FragCoord.xy;
    vec2 world = vec2(uCam.x + frag.x / uCam.z, uCam.y + (uCam.w - frag.y) / uCam.z);
    vec2 uv = vec2((world.x - uRect.x) / uRect.z, 1.0 - (world.y - uRect.y) / uRect.w);
    vec3 c = texture(uLit, uv).rgb + texture(uBloom, uv).rgb * uBloomStrength;
    // Warm highlights, cool shadows, a touch of contrast.
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(c * vec3(0.92, 0.96, 1.10), c * vec3(1.08, 1.0, 0.88), smoothstep(0.05, 0.6, l));
    c = (c - 0.5) * 1.06 + 0.5;
    vec2 s = frag / uViewSize - 0.5;
    c *= clamp(1.0 - dot(s, s) * 0.85, 0.0, 1.0);
    float n = fract(sin(dot(frag, vec2(12.9898, 78.233))) * 43758.5453);
    c += (n - 0.5) / 255.0;
    outColor = vec4(max(c, 0.0), 1.0);
}
"""
}
