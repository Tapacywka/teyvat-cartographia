#version 150

// The outline of the explored map, drawn per screen pixel (user decision 08.11.2026): the map ends on a rough line with
// a stroke and a glow into the Fog of War, and on the world map the edge breathes, swelling out and drawing back as
// water would. What does not move comes worked out per pixel of the picture (MapOutline.field, Sampler1); the line
// is the softer minimum of the lobes' line, breathing, and the guard's. The values below are MapOutline's own
// (MapOutlineMotionTest keeps them the same), and so is every step: hash, value, slices, breath, compose.

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform vec4 ColorModulator;
// The breathing's two times (MapOutline.phases) and how far it goes, in blocks (0: still).
uniform vec2 EdgePhase;
uniform float EdgeBreath;
// The surf: where the waves are in their run (0 to 1), how strong the glow is now (MapOutline.surf), and 1 where the
// setting has the waves (Map edges: animated with waves), 0 for the breathing alone.
uniform vec3 EdgeSurf;
// Blocks to a screen pixel: farther out the line keeps at least a pixel's width.
uniform float EdgePixel;
// 0 all of it; 1 floating land, the line only on land and no glow (its shadow is drawn from this too); 2 only how much
// of the map shows (the light of torches over it).
uniform float EdgeMode;
uniform vec3 EdgeStroke;
uniform vec3 EdgeGlow;

in vec2 texCoord0;
in vec2 fieldCoord;
in vec2 world;
in vec4 vertexColor;

out vec4 fragColor;

#define ROUNDING 8.0
#define END 0.7
#define STROKE 0.75
#define STROKE_SOFT 0.9
#define GLOW 12.0
#define GLOW_STRENGTH 0.34
#define STROKE_STRENGTH 0.92
#define FIELD_RANGE 32.0
#define S_FROM -12.0
#define S_TO 52.0
#define CALM_FROM 28.0
#define CALM_TO 46.0
#define PERIOD 262144
#define RIPPLE_GAP 9.0
#define RIPPLE_WIDTH 0.55
#define RIPPLE_FADE 8.0
#define RIPPLE_STRENGTH 0.5

float hash(int x, int y) {
    uint h = uint(x) * 374761393u + uint(y) * 668265263u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    return float((h ^ (h >> 16u)) & 65535u) / 65535.0;
}

float value(vec2 p, int mask) {
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    int x0 = int(i.x), y0 = int(i.y);
    int x1 = (x0 + 1) & mask, y1 = (y0 + 1) & mask;
    x0 = x0 & mask;
    y0 = y0 & mask;
    float a = hash(x0, y0), b = hash(x1, y0), c = hash(x0, y1), d = hash(x1, y1);
    return a + (b - a) * u.x + (c - a) * u.y + (a - b - c + d) * u.x * u.y;
}

float slices(vec2 p, float phase, int mask) {
    float k = floor(phase);
    float f = phase - k;
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    int ki = int(k);
    float k0 = float(ki & 1023), k1 = float((ki + 1) & 1023);
    float a = value(p + vec2(k0 * 13.7, -k0 * 7.3), mask), b = value(p + vec2(k1 * 13.7, -k1 * 7.3), mask);
    return a + (b - a) * f;
}

float breath(vec2 w) {
    float big = slices(w / 64.0, EdgePhase.x, PERIOD / 64 - 1) * 2.0 - 1.0;
    float small = slices(w / 32.0 + vec2(5.3, -3.1), EdgePhase.y, PERIOD / 32 - 1) * 2.0 - 1.0;
    return 1.5 * (0.65 * big + 0.35 * small);
}

float softMin(float a, float b, float k) {
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k / 4.0;
}

void main() {
    vec4 map = texture(Sampler0, texCoord0);
    vec4 field = texture(Sampler1, fieldCoord);
    float lobes = mix(-FIELD_RANGE, FIELD_RANGE, field.r);
    float guard = mix(-FIELD_RANGE, FIELD_RANGE, field.g);
    float s = mix(S_FROM, S_TO, field.b);
    if (EdgeBreath > 0.0 && s < CALM_TO && abs(lobes) < FIELD_RANGE) {
        lobes += EdgeBreath * breath(world) * (1.0 - smoothstep(CALM_FROM, CALM_TO, s));
    }
    float l = softMin(lobes, guard, ROUNDING);
    float end = max(END, EdgePixel * 0.5), halfWidth = max(STROKE, EdgePixel * 0.5), soft = max(STROKE_SOFT, EdgePixel * 0.6);
    float shown = min(clamp((l + end) / (2.0 * end), 0.0, 1.0), field.a);
    vec4 color;
    if (EdgeMode > 1.5) {
        color = vec4(map.rgb, map.a * shown);
    } else {
        float stroke = (1.0 - smoothstep(halfWidth, halfWidth + soft, abs(l))) * STROKE_STRENGTH;
        float reach = l >= 0.0 || s <= 0.0 ? 0.0 : smoothstep(0.0, 1.0, s / (s - l));
        float glow = l >= 0.0 ? 0.0 : exp(l / GLOW) * reach * GLOW_STRENGTH;
        float wave = 0.0;
        if (EdgeSurf.z > 0.5 && EdgeBreath > 0.0 && reach > 0.0) {
            // The surf (variant C): the glow swells and ebbs, thin waves run from the edge into the fog and fade, gone
            // where they would be thinner than a pixel.
            glow *= EdgeSurf.y;
            float d = -l, w = (fract(d / RIPPLE_GAP - EdgeSurf.x) - 0.5) * RIPPLE_GAP;
            wave = RIPPLE_STRENGTH * exp(-w * w / (2.0 * RIPPLE_WIDTH * RIPPLE_WIDTH)) * exp(-d / RIPPLE_FADE) * reach
                    * smoothstep(0.0, 2.5, d) * (1.0 - smoothstep(1.0, 2.5, EdgePixel));
        }
        if (EdgeMode > 0.5) {
            if (map.a <= 0.0) stroke = 0.0;
            glow = 0.0;
            wave = 0.0;
        }
        // The map's own pixel over the glow, the waves over them, the stroke over all (MapOutline.compose).
        float a = map.a * shown;
        float under = a + glow * (1.0 - a);
        vec3 rgb = under > 0.0 ? (map.rgb * a + EdgeGlow * glow * (1.0 - a)) / under : vec3(0.0);
        float waved = wave + under * (1.0 - wave);
        rgb = waved > 0.0 ? (EdgeStroke * wave + rgb * under * (1.0 - wave)) / waved : vec3(0.0);
        under = waved;
        float over = stroke + under * (1.0 - stroke);
        rgb = over > 0.0 ? (EdgeStroke * stroke + rgb * under * (1.0 - stroke)) / over : vec3(0.0);
        color = vec4(rgb, over);
    }
    color *= vertexColor;
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
