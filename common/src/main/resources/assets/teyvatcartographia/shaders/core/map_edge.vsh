#version 330

// A map picture (a region or a far zoom's tile) drawn with its outline: see map_edge.fsh.

#if defined(VULKAN) || defined(GL_SPIRV)
// Since 26.3 the game compiles its shaders into SPIR-V, whose stages meet by location, and its transforms come in
// another order.
#extension GL_ARB_separate_shader_objects : require
#define AT(n) layout(location = n)
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
#else
#define AT(n)
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
#endif
// The values of the picture and of the view (EdgeShader.values), which the mod's layer hands to each draw.
layout(std140) uniform EdgeParams {
    // The picture's first block, kept within the breathing's repeat (MapOutline.PERIOD), how many blocks it spans, and
    // how far in from its edges its texture is sampled (MapLayerRenderer.in).
    vec4 EdgePicture;
    // The breathing's two times (MapOutline.phases), how far it goes in blocks (0: still), blocks to a screen pixel.
    vec4 EdgeView;
    // The surf: where the waves are in their run (0 to 1), how strong the glow is now (MapOutline.surf), 1 where the
    // setting has the waves; then what is drawn: 0 all of it, 1 floating land (the line only on land and no glow; its
    // shadow is drawn from this too), 2 only how much of the map shows (the light of torches over it).
    vec4 EdgeSurfMode;
    vec4 EdgeStrokeColor;
    vec4 EdgeGlowColor;
};
#define EdgeOrigin EdgePicture.xy
#define EdgeSize EdgePicture.z
#define EdgeInset EdgePicture.w
#define EdgePhase EdgeView.xy
#define EdgeBreath EdgeView.z
#define EdgePixel EdgeView.w
#define EdgeSurf EdgeSurfMode.xyz
#define EdgeMode EdgeSurfMode.w
#define EdgeStroke EdgeStrokeColor.rgb
#define EdgeGlow EdgeGlowColor.rgb

layout(std140) uniform Projection {
    mat4 ProjMat;
};

AT(0) in vec3 Position;
AT(1) in vec2 UV0;
AT(2) in vec4 Color;

AT(0) out vec2 texCoord0;
AT(1) out vec2 fieldCoord;
AT(2) out vec2 world;
AT(3) out vec4 vertexColor;

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
