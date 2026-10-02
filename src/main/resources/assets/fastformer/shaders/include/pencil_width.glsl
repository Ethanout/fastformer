uniform float PencilGrain;
uniform float PencilNearWidth;

float pencilWidth(float width, float distanceToEye) {
    float nearWeight = 1.0 - smoothstep(3.0, 16.0, distanceToEye);
    float boost = mix(1.0, max(PencilNearWidth, 1.0), nearWeight);
    return width * mix(1.0, boost, clamp(PencilGrain, 0.0, 1.0));
}
