#version 150

in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float LineWidth;
uniform vec2 ScreenSize;
#moj_import <fastformer:pencil_width.glsl>
out vec4 vertexColor;
// Cross-stroke coordinate so the halo can fade out instead of ending on a hard edge.
noperspective out float acrossPx;
noperspective out float strokeHalfWidth;

const float VIEW_SHRINK = 1.0 - (1.0 / 256.0);
const mat4 VIEW_SCALE = mat4(
    VIEW_SHRINK, 0.0, 0.0, 0.0,
    0.0, VIEW_SHRINK, 0.0, 0.0,
    0.0, 0.0, VIEW_SHRINK, 0.0,
    0.0, 0.0, 0.0, 1.0
);

void main() {
    vec4 linePosStart = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position, 1.0);
    vec4 linePosEnd = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position + Normal, 1.0);

    vec3 ndc1 = linePosStart.xyz / linePosStart.w;
    vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;
    vec2 lineScreenDirection = (ndc2.xy - ndc1.xy) * ScreenSize;
    lineScreenDirection /= max(length(lineScreenDirection), 0.00001);
    float width = pencilWidth(LineWidth, length((ModelViewMat * vec4(Position, 1.0)).xyz));
    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * width / ScreenSize;
    if (lineOffset.x < 0.0) {
        lineOffset *= -1.0;
    }
    float side = (gl_VertexID % 2 == 0) ? 1.0 : -1.0;
    if (side > 0.0) {
        gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    } else {
        gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    }

    vertexColor = Color;
    strokeHalfWidth = width * 0.5;
    acrossPx = side * strokeHalfWidth;
}
