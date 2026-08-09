#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 ScreenSize;
uniform float ReticleFromMode;
uniform float ReticleMode;
uniform float ReticleTransition;
uniform float ReticleCrossArm;
uniform float ReticleCrossWidth;
uniform float ReticleArmLength;
uniform float ReticleArmWidth;
uniform float ReticleArmGap;
uniform float ReticleOffsetX;
uniform float ReticleOffsetY;
uniform float ReticlePointRingRadius;
uniform float ReticlePointRingWidth;
uniform float ReticlePointCenterSize;
uniform float ReticleOpacity;

in vec2 texCoord;
out vec4 fragColor;

const float EIGHTH_TURN = 0.78539816339;

float boxDistance(vec2 point, vec2 halfSize) {
    vec2 outside = abs(point) - halfSize;
    return length(max(outside, vec2(0.0))) + min(max(outside.x, outside.y), 0.0);
}

float boxMask(vec2 point, vec2 halfSize) {
    float distanceToBox = boxDistance(point, halfSize);
    float antiAlias = max(0.5, fwidth(distanceToBox));
    return 1.0 - smoothstep(-antiAlias, antiAlias, distanceToBox);
}

float orientedBox(vec2 point, vec2 center, vec2 axis, vec2 halfSize) {
    vec2 normal = vec2(-axis.y, axis.x);
    vec2 local = vec2(dot(point - center, axis), dot(point - center, normal));
    return boxMask(local, halfSize);
}

vec2 rotateClockwise(vec2 point, float angle) {
    float sine = sin(angle);
    float cosine = cos(angle);
    return vec2(cosine * point.x + sine * point.y, -sine * point.x + cosine * point.y);
}

float normalCross(vec2 point) {
    float horizontal = boxMask(point, vec2(ReticleCrossArm, ReticleCrossWidth * 0.5));
    float vertical = boxMask(point, vec2(ReticleCrossWidth * 0.5, ReticleCrossArm));
    return max(horizontal, vertical);
}

float radialArm(vec2 point, vec2 cardinal, float morph, float clockwiseAngle) {
    vec2 axis = rotateClockwise(cardinal, clockwiseAngle);
    float lengthToEnd = mix(ReticleCrossArm, ReticleArmLength, morph);
    float width = mix(ReticleCrossWidth, ReticleArmWidth, morph);
    float gap = ReticleArmGap * morph;
    vec2 center = axis * (gap + lengthToEnd * 0.5);
    return orientedBox(point, center, axis, vec2(lengthToEnd * 0.5, width * 0.5));
}

float embeddedShape(vec2 point, float morph, float clockwiseAngle) {
    float top = radialArm(point, vec2(0.0, 1.0), morph, clockwiseAngle);
    float right = radialArm(point, vec2(1.0, 0.0), morph, clockwiseAngle);
    float bottom = radialArm(point, vec2(0.0, -1.0), morph, clockwiseAngle);
    float left = radialArm(point, vec2(-1.0, 0.0), morph, clockwiseAngle);
    return max(max(top, right), max(bottom, left));
}

float ringMask(vec2 point, float radius, float width) {
    float distanceToRing = abs(length(point) - radius) - width * 0.5;
    float antiAlias = max(0.5, fwidth(distanceToRing));
    return 1.0 - smoothstep(-antiAlias, antiAlias, distanceToRing);
}

float halfGridShape(vec2 point) {
    float center = boxMask(point, vec2(ReticlePointCenterSize * 0.5));
    float ring = ringMask(point, ReticlePointRingRadius, ReticlePointRingWidth);
    return max(center, ring);
}

float shapeForMode(vec2 point, float mode) {
    if (mode < 0.5) {
        return normalCross(point);
    }
    if (mode < 1.5) {
        return embeddedShape(point, 1.0, EIGHTH_TURN);
    }
    return halfGridShape(point);
}

void main() {
    vec4 source = texture(DiffuseSampler, texCoord);
    vec2 fromCenter = gl_FragCoord.xy - ScreenSize * 0.5;
    vec2 reticlePoint = fromCenter - vec2(ReticleOffsetX, ReticleOffsetY);
    float transition = clamp(ReticleTransition, 0.0, 1.0);
    transition = transition * transition * (3.0 - 2.0 * transition);
    float shape;
    if (ReticleFromMode < 0.5 && ReticleMode > 0.5 && ReticleMode < 1.5) {
        shape = embeddedShape(reticlePoint, transition, EIGHTH_TURN * transition);
    } else if (ReticleFromMode > 0.5 && ReticleFromMode < 1.5 && ReticleMode < 0.5) {
        shape = embeddedShape(reticlePoint, 1.0 - transition, EIGHTH_TURN * (1.0 + transition));
    } else {
        float fromShape = shapeForMode(reticlePoint, ReticleFromMode);
        float toShape = shapeForMode(reticlePoint, ReticleMode);
        shape = mix(fromShape, toShape, transition);
    }
    float amount = clamp(shape * ReticleOpacity, 0.0, 1.0);

    vec3 inverted = vec3(1.0) - source.rgb;
    fragColor = vec4(mix(source.rgb, inverted, amount), source.a);
}
