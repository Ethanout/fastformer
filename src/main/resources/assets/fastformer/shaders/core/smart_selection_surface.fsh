#version 150

uniform vec4 ColorModulator;
uniform vec3 InkColor;
uniform float HatchScale;
uniform float HatchOpacity;
in vec3 surfacePosition;
in float vertexAlpha;
out vec4 fragColor;

float pencil(vec2 face, float slope) {
    float along = (face.x + slope * face.y) * HatchScale;
    float stroke = floor(along);
    float bend = 0.045 * sin(face.y * 9.0 + stroke * 2.17);
    float distanceToLine = abs(fract(along + bend) - 0.5);
    float aa = max(fwidth(along), 0.015);
    float line = 1.0 - smoothstep(0.065, 0.065 + aa, distanceToLine);
    float grain = 0.65 + 0.35 * fract(sin(dot(floor(face * 192.0), vec2(12.9898, 78.233))) * 43758.5453);
    return line * grain;
}

void main() {
    vec3 normal = abs(normalize(cross(dFdx(surfacePosition), dFdy(surfacePosition))));
    vec2 face = normal.y > normal.x && normal.y > normal.z ? surfacePosition.xz
        : normal.x > normal.z ? surfacePosition.zy : surfacePosition.xy;
    float hatch = max(pencil(face, 0.65), pencil(face, -0.65) * 0.3);
    float shade = dot(normal, vec3(0.8, 1.0, 0.7));
    float alpha = HatchOpacity * (0.06 + 0.94 * hatch) * vertexAlpha * ColorModulator.a;
    fragColor = vec4(InkColor * shade * ColorModulator.rgb, alpha);
}
