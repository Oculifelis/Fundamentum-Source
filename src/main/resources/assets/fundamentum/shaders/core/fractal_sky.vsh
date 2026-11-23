#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;

layout(location = 0) out vec3 skyDir;

// A cube round the camera; the fragment shader turns each pixel's view direction into a point of the fractal.
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    skyDir = Position;
}
