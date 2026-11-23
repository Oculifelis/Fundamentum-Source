#version 150


// Fundamentum's "melting" vision (sky/FractalSkiesClient), drawn over the world: a slow wobble and sag, a gentle
// breathing zoom, a mild hue drift and colour fringing at the edges. Melt.x is the strength (0..1),
// Melt.y the time in seconds. Everything moves at under 0.5 radians a second: no flashing or strobing.

uniform sampler2D Sampler0;
uniform vec4 Melt;

in vec2 texCoord;

out vec4 fragColor;

vec3 hueShift(vec3 c, float a) {
    const mat3 toYiq = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);
    const mat3 toRgb = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);
    vec3 yiq = toYiq * c;
    float ca = cos(a), sa = sin(a);
    yiq.yz = mat2(ca, sa, -sa, ca) * yiq.yz;
    return toRgb * yiq;
}

void main() {
    float k = clamp(Melt.x, 0.0, 1.0);
    float t = Melt.y;
    vec2 uv = texCoord;
    // breathing: a slow zoom about the centre
    uv = 0.5 + (uv - 0.5) * (1.0 - 0.006 * k * (0.5 + 0.5 * sin(t * 0.45)));
    // wobble: two slow sine layers each way
    vec2 w = vec2(sin(uv.y * 6.0 + t * 0.35) + 0.5 * sin(uv.y * 13.0 - t * 0.23),
                  cos(uv.x * 5.0 + t * 0.29) + 0.5 * cos(uv.x * 11.0 + t * 0.17));
    uv += w * 0.0035 * k;
    // sag: things seem to droop a little, more toward the bottom of the screen
    uv.y += 0.004 * k * (0.5 + 0.5 * sin(uv.x * 17.0 + t * 0.15)) * (1.0 - uv.y);
    // fringing, growing toward the edges
    vec2 off = (uv - 0.5) * 0.006 * k;
    vec3 col = vec3(texture(Sampler0, uv + off).r, texture(Sampler0, uv).g, texture(Sampler0, uv - off).b);
    // a mild hue drift and a little more saturation
    col = hueShift(col, 0.35 * k * sin(t * 0.11));
    float grey = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(grey), col, 1.0 + 0.2 * k);
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
