#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;

out vec4 fragColor;

// The frame's colours, opaque: its alpha is left anywhere between 0 and 1 by the game (0 under VulkanMod).
void main() {
    fragColor = vec4(texture(DiffuseSampler, texCoord).rgb, 1.0);
}
