shader_type canvas_item;
// Applies the light map and fog of war: unseen-but-explored areas become a cold memory.

uniform sampler2D light_tex : filter_nearest;
uniform sampler2D vis_tex : filter_linear;
uniform vec4 rect;    // scene origin x, y, width, height (world pixels)
uniform vec2 map_px;  // dungeon size in world pixels

void fragment() {
	vec4 s = texture(TEXTURE, UV);
	vec3 scene = s.rgb * s.a;
	vec3 light = texture(light_tex, UV).rgb * 2.0;
	vec2 world = rect.xy + UV * rect.zw;
	vec2 vis = texture(vis_tex, world / map_px).rg;
	float seen = smoothstep(0.1, 0.6, vis.r);
	float known = smoothstep(0.2, 0.8, vis.g);
	float lum = dot(scene, vec3(0.299, 0.587, 0.114));
	vec3 memory = mix(vec3(lum), scene, 0.25) * vec3(0.30, 0.34, 0.50);
	vec3 lit = scene * light;
	COLOR = vec4(mix(memory * known, lit, seen), 1.0);
}
