#version 150

uniform sampler2D SelectionColor;
uniform sampler2D SelectionDepth;
uniform sampler2D WorldDepth;
uniform mat4 InverseProjection;

in vec2 texCoord;
out vec4 fragColor;

float viewDepth(float depth) {
    vec4 view = InverseProjection * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return -view.z / view.w;
}

void main() {
    vec4 selected = texture(SelectionColor, texCoord);
    if (selected.a < 0.01) discard;

    float world = texture(WorldDepth, texCoord).r;
    float cell = texture(SelectionDepth, texCoord).r;
    // Compare in blocks, so the tolerance does not grow with distance.
    bool occluded = world < 1.0 && viewDepth(cell) > viewDepth(world) + 0.02;
    // Blend occluded selection cells evenly with the world.
    fragColor = vec4(selected.rgb, selected.a * (occluded ? 0.5 : 1.0));
}
