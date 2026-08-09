#version 150

uniform vec4 ColorModulator;
uniform float DashOffset;

in float dashDistance;
in vec4 vertexColor;

out vec4 fragColor;

const float DASH_LENGTH = 0.25;
const float DASH_PERIOD = 0.5;

void main() {
    float phase = mod(dashDistance - DashOffset + DASH_PERIOD, DASH_PERIOD);
    float shade = phase < DASH_LENGTH ? 1.0 : 0.0;
    vec4 color = vertexColor * ColorModulator;
    fragColor = vec4(vec3(shade), color.a);
}
