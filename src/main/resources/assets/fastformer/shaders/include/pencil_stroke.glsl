in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 CameraRotation;
uniform vec3 CameraPosition;
uniform float LineWidth;
uniform vec2 ScreenSize;

#moj_import <fastformer:pencil_width.glsl>

out vec4 vertexColor;
noperspective out float acrossPx;
noperspective out float strokeHalfWidth;
out float distanceToEye;
out float worldAlong;
out vec3 strokeWorld;

const float VIEW_SHRINK = 1.0 - (1.0 / 256.0);

void main() {
    vec4 viewStart = ModelViewMat * vec4(Position, 1.0);
    vec4 viewEnd = ModelViewMat * vec4(Position + Normal, 1.0);
    vec4 clipStart = ProjMat * vec4(viewStart.xyz * VIEW_SHRINK, viewStart.w);
    vec4 clipEnd = ProjMat * vec4(viewEnd.xyz * VIEW_SHRINK, viewEnd.w);
    vec2 screenDir = (clipEnd.xy / clipEnd.w - clipStart.xy / clipStart.w) * ScreenSize;
    screenDir /= max(length(screenDir), 0.00001);
    vec2 perpendicular = vec2(-screenDir.y, screenDir.x);
    if (perpendicular.x < 0.0) perpendicular = -perpendicular;

    float side = (gl_VertexID % 2 == 0) ? 1.0 : -1.0;
    distanceToEye = length(viewStart.xyz);
    float width = pencilWidth(LineWidth, distanceToEye);
    vec2 offset = perpendicular * side * width / ScreenSize;
    gl_Position = clipStart + vec4(offset * clipStart.w, 0.0, 0.0);

    // Reconstruct world coordinates, including the current mesh transform.
    // Grain stays attached to the mark when the camera moves.
    strokeWorld = (CameraRotation * viewStart).xyz + CameraPosition;
    vec3 worldDirection = normalize(mat3(CameraRotation * ModelViewMat) * Normal);
    worldAlong = dot(strokeWorld, worldDirection);
#ifdef FF_STROKE_UV
    // CPU strokes carry distances along the original edge, before jitter or bow.
    if (UV0.y > 0.5) worldAlong = UV0.x;
#endif
    strokeHalfWidth = width * 0.5;
    acrossPx = side * strokeHalfWidth;
    vertexColor = Color;
}
