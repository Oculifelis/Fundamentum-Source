#version 330

// Fundamentum's animated whip cords (WhipCordFx). Drawn with core/entity's vertex shader (EMISSIVE, NO_OVERLAY,
// NO_CARDINAL_LIGHTING): u runs across the cord (0..1), v along it from the handle (0) to the tip (1).

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void main() {
    // GameTime is the day's fraction (24000 ticks = 1200 s).
    float t = GameTime * 1200.0;
    vec2 uv = texCoord0;
    float across = abs(uv.x - 0.5) * 2.0;
    float along = uv.y;
    vec4 color = texture(Sampler0, uv) * vertexColor * ColorModulator;

#ifdef FX_GLOW
    if (color.a < 0.1) {
        discard;
    }
#endif

#ifdef FX_ENERGY
    float band = 0.5 + 0.5 * sin(along * 22.0 - t * 10.0);
    float core = 1.0 - smoothstep(0.0, 1.0, across);
    color.rgb = color.rgb * (0.5 + band * core) + vec3(core * core * 0.4);
    color.a *= core * (0.55 + 0.45 * band);
#endif

#ifdef FX_WATER
    float shimmer = noise(vec2(uv.x * 3.0 + t * 0.7, along * 9.0 - t * 2.2));
    float foam = smoothstep(0.62, 1.0, across) * (0.5 + 0.5 * noise(vec2(along * 26.0 - t * 4.0, uv.x * 5.0)));
    color.rgb = mix(color.rgb * (0.85 + 0.35 * shimmer), vec3(0.88, 0.97, 1.0), clamp(foam * 0.85, 0.0, 1.0));
    color.a *= 0.62 + 0.25 * shimmer + 0.3 * foam;
#endif

#ifdef FX_WIND
    float streak = noise(vec2(uv.x * 3.5 + along * 3.0, along * 12.0 - t * 7.0));
    float swirl = noise(vec2(uv.x * 7.0 - t * 1.5, along * 5.0 + t * 1.1));
    color.rgb = mix(color.rgb, vec3(1.0), 0.35 * streak);
    color.a *= smoothstep(0.3, 0.85, streak * 0.7 + swirl * 0.45) * (1.0 - 0.55 * across);
    if (color.a < 0.02) {
        discard;
    }
#endif

#ifdef FX_LIGHTNING
    float frame = floor(t * 18.0);
    float jag = noise(vec2(along * 28.0, frame)) - 0.5;
    float line = 1.0 - smoothstep(0.0, 0.18 + 0.12 * hash(vec2(frame, 3.0)), abs(uv.x - 0.5 + jag * 0.55));
    float flicker = 0.65 + 0.6 * step(0.3, hash(vec2(frame, 7.0)));
    color.rgb = mix(color.rgb, vec3(1.0), line * 0.85) * flicker;
    color.a = max(line, color.a * 0.35) * vertexColor.a;
#endif

#ifdef FX_FIRE
    // Flames rise toward the tip and flicker; the edges break up into tongues.
    float flame = noise(vec2(uv.x * 4.0 + t * 1.3, along * 14.0 - t * 9.0)) * 0.65
        + noise(vec2(uv.x * 9.0 - t * 2.1, along * 31.0 - t * 15.0)) * 0.35;
    float body = 1.0 - smoothstep(0.25, 1.0, across + (0.5 - flame) * 0.9);
    vec3 hot = mix(vec3(0.85, 0.18, 0.02), vec3(1.0, 0.62, 0.12), flame);
    hot = mix(hot, vec3(1.0, 0.95, 0.7), smoothstep(0.55, 0.95, body * flame + (1.0 - across) * 0.35));
    color.rgb = mix(color.rgb, hot, 0.75) * (0.8 + 0.4 * flame);
    color.a = clamp(body * (0.75 + 0.5 * flame), 0.0, 1.0) * vertexColor.a;
    if (color.a < 0.02) {
        discard;
    }
#endif

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
