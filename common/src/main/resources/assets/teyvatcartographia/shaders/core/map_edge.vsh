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
// How far in from its edges the picture's texture is sampled (MapLayerRenderer.in): taken off again for the world
// position and the field, so both run on exactly into the next picture.
uniform float EdgeInset;

out vec2 texCoord0;
out vec2 fieldCoord;
out vec2 world;
out vec4 vertexColor;

// The picture's pixels a side; its field has a border of one pixel more around them (MapOutline.field).
#define TEXELS 256.0

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vec2 t = (UV0 - EdgeInset) / (1.0 - 2.0 * EdgeInset);
    world = EdgeOrigin + t * EdgeSize;
    fieldCoord = (t * TEXELS + 1.0) / (TEXELS + 2.0);
    vertexColor = Color;
}
