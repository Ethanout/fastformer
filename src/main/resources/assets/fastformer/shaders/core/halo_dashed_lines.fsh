#version 150

uniform vec4 ColorModulator;

in vec4 vertexColor;
noperspective in float acrossPx;
noperspective in float strokeHalfWidth;

out vec4 fragColor;

// Same soft edge as the solid halo. The dashed vertex shader already carries acrossPx.
void main() {
    float halfWidth = max(strokeHalfWidth, 0.0001);
    float fade = max(max(fwidth(acrossPx), 0.65), halfWidth * 0.35);
    float coverage = 1.0 - smoothstep(halfWidth - fade, halfWidth, abs(acrossPx));
    fragColor = vec4(ColorModulator.rgb, vertexColor.a * ColorModulator.a * coverage);
}
