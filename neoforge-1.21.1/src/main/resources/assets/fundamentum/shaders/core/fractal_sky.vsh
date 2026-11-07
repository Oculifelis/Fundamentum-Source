#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 skyDir;

// A cube round the camera; the fragment shader turns each pixel's view direction into a point of the fractal.
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    skyDir = Position;
}
