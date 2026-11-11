shader_type canvas_item;
// Pixel-perfect upscale of the lit scene with bloom, colour grading, vignette and dithering.

uniform vec2 texel;          // 1 / lit texture size
uniform vec2 view_origin;    // map view top-left (screen pixels)
uniform vec2 view_size;      // map view size (screen pixels)
uniform float bloom_strength = 0.85;
uniform float threshold = 0.72;

vec3 bright(vec3 c) {
	float l = max(c.r, max(c.g, c.b));
	return c * (max(l - threshold, 0.0) / max(l, 0.0001));
}

void fragment() {
	vec3 c = texture(TEXTURE, UV).rgb;
	vec3 b = vec3(0.0);
	for (int i = 0; i < 8; i++) {
		float a = float(i) * 0.785398;
		vec2 d = vec2(cos(a), sin(a));
		b += bright(texture(TEXTURE, UV + d * texel * 2.0).rgb) * 0.09;
		b += bright(texture(TEXTURE, UV + d * texel * 4.5).rgb) * 0.05;
	}
	c += b * bloom_strength;
	// Warm highlights, cool shadows, a touch of contrast.
	float l = dot(c, vec3(0.299, 0.587, 0.114));
	c = mix(c * vec3(0.92, 0.96, 1.10), c * vec3(1.08, 1.0, 0.88), smoothstep(0.05, 0.6, l));
	c = (c - 0.5) * 1.06 + 0.5;
	vec2 s = (FRAGCOORD.xy - view_origin) / view_size - 0.5;
	c *= clamp(1.0 - dot(s, s) * 0.85, 0.0, 1.0);
	float n = fract(sin(dot(FRAGCOORD.xy, vec2(12.9898, 78.233))) * 43758.5453);
	c += (n - 0.5) / 255.0;
	COLOR = vec4(max(c, 0.0), 1.0);
}
