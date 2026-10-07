#version 150

in vec3 Position;
in vec4 Color;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ShellOrigin;
out vec3 surfacePosition;
out float vertexAlpha;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    surfacePosition = Position + ShellOrigin;
    vertexAlpha = Color.a;
}
