#version 150
uniform sampler2D PreviewColor;
uniform sampler2D PreviewDepth;
uniform mat4 InverseViewProjection;
uniform vec3 CameraPosition;
uniform vec4 PreviewTint;
uniform float HatchStrength;
uniform float HatchScale;
uniform float BoilFrame;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    vec4 material = texture(PreviewColor, texCoord);
    if (material.a < 0.01) discard;
    // The transparent target accumulates premultiplied color through source-over blending.
    material.rgb /= material.a;
    float depth = texture(PreviewDepth, texCoord).r;
    vec4 relative = InverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 world = relative.xyz / relative.w + CameraPosition;
    vec3 normal = abs(normalize(cross(dFdx(world), dFdy(world))));
    vec3 weights = pow(normal, vec3(8.0));
    weights /= max(dot(weights, vec3(1.0)), 0.00001);
    // Each plane has its own world-anchored pencil coordinates.
    vec3 phase = vec3(world.y + world.z, world.x + world.z, world.x + world.y) * HatchScale;
    vec3 width = max(fwidth(phase), vec3(0.01));
    vec3 hatch = 1.0 - smoothstep(vec3(0.10), vec3(0.10) + width, abs(fract(phase) - 0.5));
    float pigment = fract(sin(dot(floor(world * 96.0), vec3(127.1, 311.7, 74.7)) + BoilFrame) * 43758.5453);
    float shade = 1.0 - HatchStrength * (dot(hatch, weights) * 0.65 + (1.0 - pigment) * 0.35);
    gl_FragDepth = depth;
    fragColor = vec4(material.rgb * PreviewTint.rgb * shade, material.a * PreviewTint.a);
}
