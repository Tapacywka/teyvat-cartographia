#version 150

// A map picture (a region or a far zoom's tile) drawn with its outline: see map_edge.fsh.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// The picture's first block, kept within the breathing's repeat (MapOutline.PERIOD), and how many blocks it spans.
uniform vec2 EdgeOrigin;
uniform float EdgeSize;

out vec2 texCoord0;
out vec2 world;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    world = EdgeOrigin + UV0 * EdgeSize;
    vertexColor = Color;
}
