uniform vec4 ColorModulator;
uniform float PencilGrain;
uniform float BoilFrame;
uniform vec2 OccludedDash;
uniform float OccludedOpacity;
uniform float FFGuiScale;

in vec4 vertexColor;
noperspective in float acrossPx;
noperspective in float strokeHalfWidth;
in float distanceToEye;
in float worldAlong;
in vec3 strokeWorld;
out vec4 fragColor;

float pencilHash(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

void main() {
    vec4 color = vertexColor * ColorModulator;
    float sketch = clamp(PencilGrain, 0.0, 1.0);
    float halfWidth = strokeHalfWidth;
    float guiScale = max(FFGuiScale, 1.0);
    float logicalAcross = acrossPx / guiScale;
    float edgeAA = max(fwidth(acrossPx), 0.65);
    float cleanEdge = 1.0 - smoothstep(halfWidth - edgeAA, halfWidth, abs(acrossPx));

    // Vary the deposit across the stroke, rather than dimming the whole line.
    vec3 grainSeed = vec3(floor(logicalAcross * 5.0), BoilFrame, 0.0);
    float fineGrain = pencilHash(floor(strokeWorld * 96.0) + grainSeed);
    float coarseGrain = pencilHash(floor(strokeWorld * 12.0) + grainSeed);
    float farWeight = smoothstep(6.0, 40.0, distanceToEye);
    float grain = mix(fineGrain, coarseGrain, farWeight);
    float fineEdge = pencilHash(vec3(floor(worldAlong * 32.0), sign(acrossPx), BoilFrame));
    float coarseEdge = pencilHash(vec3(floor(worldAlong * 6.0), sign(acrossPx), BoilFrame));
    float edgeGrain = mix(fineEdge, coarseEdge, farWeight);
    float roughWidth = halfWidth - (0.05 + edgeGrain * 0.2) * guiScale;
    float roughEdge = 1.0 - smoothstep(roughWidth - edgeAA, roughWidth, abs(acrossPx));
    float tooth = smoothstep(0.08, 0.5, grain);
    float fibers = 0.5 + 0.5 * sin(logicalAcross * 18.0 + sin(worldAlong * 12.0) * 0.65);
    float pigment = (0.8 + 0.2 * tooth) * (0.9 + 0.1 * fibers);
    float core = 1.0 - smoothstep(halfWidth * 0.3, halfWidth * 0.7, abs(acrossPx));
    float crayon = max(roughEdge * pigment, core * 0.9);
    float coverage = mix(cleanEdge, crayon, sketch);
    if (OccludedDash.x > 0.0) {
        float period = OccludedDash.x + OccludedDash.y;
        float duty = OccludedDash.x / period;
        float phase = abs(fract(worldAlong / period) * 2.0 - 1.0);
        float aa = min(0.2, fwidth(worldAlong / period));
        coverage *= 1.0 - smoothstep(duty - max(aa, 0.001), duty + max(aa, 0.001), phase);
    }
    fragColor = vec4(color.rgb, color.a * coverage * OccludedOpacity);
}
