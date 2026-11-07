#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

// Fundamentum's fractal sky (sky/FractalSkiesClient). The parameters ride in TextureMat, column by column:
//   [0] type (0 mandelbrot, 1 julia, 2 burning ship), iterations, time in seconds, alpha (fade in and out)
//   [1] zoom speed (cycles a second), zoom depth (e-folds), palette speed, julia morph speed
//   [2] centre x, centre y, brightness, rotation speed (turns a second)
//   [3] palette, horizon (elevation where it is fully shown), mapping (0 zenith, 1 three axes), scale
// ModelOffset.x is 1 for a tour: the whole Mandelbrot set spans the sky (centred on the centre, scale wide) and each
// zoom cycle dives into the next of seven famous spots and back out, so it never shows the same dive twice in a row.
// ColorModulator.rgb tints it. The zoom goes in and back out on a cosine, never past float precision.
// Everything comes from the normalised view direction per pixel, never from the dome's faces or an angle (no atan2), so
// there is no seam, corner, crease or wrap-around line anywhere and no pinching overhead.

layout(location = 0) in vec3 skyDir;

layout(location = 0) out vec4 fragColor;

const float TAU = 6.2831853;

vec3 palette(float t, int p) {
    vec3 a = vec3(0.5), b = vec3(0.5), c = vec3(1.0), d = vec3(0.0, 0.33, 0.67);   // 0 rainbow
    if (p == 1) { c = vec3(1.0, 0.7, 0.4); d = vec3(0.0, 0.15, 0.2); }             // fire
    else if (p == 2) { a = vec3(0.45, 0.6, 0.8); b = vec3(0.35, 0.35, 0.25); d = vec3(0.0, 0.1, 0.2); }   // ice
    else if (p == 3) { c = vec3(2.0, 1.0, 0.0); d = vec3(0.5, 0.2, 0.25); }        // acid
    else if (p == 4) { c = vec3(1.0, 1.0, 1.0); d = vec3(0.8, 0.9, 0.3); }         // neon
    else if (p == 5) { a = vec3(0.4, 0.2, 0.5); b = vec3(0.4, 0.3, 0.4); c = vec3(1.0, 1.0, 0.8); d = vec3(0.3, 0.6, 0.9); }   // void
    return a + b * cos(TAU * (c * t + d));
}

// Famous Mandelbrot spots on the set's edge, where zooming in keeps finding new detail.
const vec2 SPOTS[7] = vec2[7](
    vec2(-0.743643887, 0.131825904),    // seahorse valley
    vec2(-0.16070135, 1.0375665),       // a spiral on the top bulb's antenna
    vec2(0.3245046, 0.0485510),         // elephant valley spirals
    vec2(-1.40115519, 0.0),             // the Feigenbaum point: bulbs on bulbs on the real axis
    vec2(-0.1011, 0.9563),              // the branching tip of the top bulb
    vec2(-1.25066, 0.02012),            // spirals between the big bulbs
    vec2(0.001643722, -0.822467633));   // a deep spiral below

int type;
int iterations;
float time;
float morph;
vec2 centre;
int pal;
float paletteSpeed;
float spin;
float s;

vec3 fractal(vec2 p) {
    float r = time * spin * TAU;
    p = mat2(cos(r), -sin(r), sin(r), cos(r)) * p;
    vec2 z, c;
    if (type == 1) {
        // c walks just outside the Mandelbrot main cardioid, where every Julia set is full of spirals and filaments
        // (a fixed radius like 0.7885 passes through Cantor dust that reads as one flat colour)
        float m = 2.1 + time * morph * TAU;
        c = 1.015 * (0.5 * vec2(cos(m), sin(m)) - 0.25 * vec2(cos(2.0 * m), sin(2.0 * m)));
        z = centre + p * s;
    } else {
        c = centre + p * s;
        z = vec2(0.0);
    }
    float n = 0.0;
    float m2 = 0.0;
    for (int i = 0; i < 500; i++) {
        if (i >= iterations) break;
        if (type == 2) z = abs(z);
        z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
        m2 = dot(z, z);
        if (m2 > 256.0) break;
        n += 1.0;
    }
    if (n >= float(iterations)) {
        // inside the set: a dim, slowly shifting colour from where the orbit settled, not a flat black hole
        return palette(0.6 + 0.35 * length(z) + time * paletteSpeed, pal) * 0.35;
    }
    float smoothN = n - log2(log2(m2)) + 4.0;
    // log scale: wide bands far out, and they don't turn to grain where the count climbs near the edge
    return palette(log2(max(smoothN, 1.0)) * 0.35 + time * paletteSpeed, pal);
}

void main() {
    vec3 dir = normalize(skyDir);
    type = int(TextureMat[0][0] + 0.5);
    iterations = int(TextureMat[0][1] + 0.5);
    time = TextureMat[0][2];
    float alpha = TextureMat[0][3];
    float zoomSpeed = TextureMat[1][0];
    float zoomDepth = TextureMat[1][1];
    paletteSpeed = TextureMat[1][2];
    morph = TextureMat[1][3];
    centre = vec2(TextureMat[2][0], TextureMat[2][1]);
    float brightness = TextureMat[2][2];
    spin = TextureMat[2][3];
    pal = int(TextureMat[3][0] + 0.5);
    float horizon = TextureMat[3][1];
    float mapping = TextureMat[3][2];
    float scale = TextureMat[3][3];
    if (ModelOffset.x > 0.5 && type != 1) {
        // tour: zoom about this cycle's spot, so the whole set shrinks toward it and then grows back
        float leg = time * zoomSpeed;
        int spot = int(mod(floor(leg) * 3.0, 7.0));
        float u = 0.5 - 0.5 * cos(fract(leg) * TAU);
        s = scale * exp(-zoomDepth * u);
        centre = SPOTS[spot] + (centre - SPOTS[spot]) * (s / scale);
        iterations = min(500, iterations + int(u * zoomDepth * 30.0));
    } else {
        s = scale * exp(-zoomDepth * (0.5 - 0.5 * cos(time * zoomSpeed * TAU)));
    }

    vec3 col;
    if (mapping < 0.5) {
        // centred on the zenith: stereographic, smooth through the zenith with no pole; mirrored below the horizon,
        // where the dome has already faded out
        col = fractal(dir.xz / (1.0 + abs(dir.y)));
    } else {
        // three axes: the fractal seen along each axis, blended by how squarely you look along it. Each projection is
        // continuous over the sphere, and where it would squash (side on) its weight is already near zero.
        // The weights reach exactly zero smoothly (no cut-off step), so a projection that isn't needed is skipped.
        vec3 w = pow(abs(dir), vec3(8.0));
        w = max(w / (w.x + w.y + w.z) - 0.02, 0.0);
        w /= w.x + w.y + w.z;
        col = vec3(0.0);
        if (w.y > 0.0) col += w.y * fractal(dir.xz * 1.4);
        if (w.x > 0.0) col += w.x * fractal(dir.zy * 1.4);
        if (w.z > 0.0) col += w.z * fractal(dir.xy * 1.4);
    }
    col *= brightness * ColorModulator.rgb;
    // dim and fade toward the horizon, so the vanilla sky and fog meet it there
    float h = smoothstep(-0.05, max(horizon, 0.0), dir.y);
    col *= mix(0.55, 1.0, h);
    fragColor = vec4(col, alpha * h);
}
