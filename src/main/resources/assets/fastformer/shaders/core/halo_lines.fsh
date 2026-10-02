#version 150

uniform vec4 ColorModulator;

in vec4 vertexColor;
noperspective in float acrossPx;
noperspective in float strokeHalfWidth;

out vec4 fragColor;

// Paints the modulator colour; only the vertex alpha survives, so one mesh serves ink and halo.
// The edge fades over a band that scales with the line width, so haloed strokes stay soft and
// faint instead of ending on the hard edge of a wider quad.
void main() {
    float halfWidth = max(strokeHalfWidth, 0.0001);
    float fade = max(max(fwidth(acrossPx), 0.65), halfWidth * 0.35);
    float coverage = 1.0 - smoothstep(halfWidth - fade, halfWidth, abs(acrossPx));
    fragColor = vec4(ColorModulator.rgb, vertexColor.a * ColorModulator.a * coverage);
}
