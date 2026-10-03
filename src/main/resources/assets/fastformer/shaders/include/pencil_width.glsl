uniform float PencilGrain;
uniform float PencilNearWidth;
uniform int WidthCurveCount;
uniform vec4 WidthCurve0;
uniform vec4 WidthCurve1;
uniform vec4 WidthCurve2;
uniform vec4 WidthCurve3;
uniform vec4 WidthCurve4;
uniform vec4 WidthCurve5;
uniform vec4 WidthCurve6;
uniform vec4 WidthCurve7;

float widthAtDistance(float distanceToEye) {
    vec4 nodes[8] = vec4[8](WidthCurve0, WidthCurve1, WidthCurve2, WidthCurve3,
        WidthCurve4, WidthCurve5, WidthCurve6, WidthCurve7);
    vec4 a = nodes[0];
    if (distanceToEye <= a.x) return a.y;
    for (int i = 1; i < WidthCurveCount && i < 8; i++) {
        vec4 b = nodes[i];
        if (distanceToEye < b.x) {
            float h = b.x - a.x;
            float t = (distanceToEye - a.x) / h;
            float t2 = t*t, t3 = t2*t;
            float value = (2.0*t3-3.0*t2+1.0)*a.y + (t3-2.0*t2+t)*h*a.z
                + (-2.0*t3+3.0*t2)*b.y + (t3-t2)*h*b.z;
            return clamp(value, min(a.y,b.y), max(a.y,b.y));
        }
        a = b;
    }
    return a.y;
}

float pencilWidth(float width, float distanceToEye) {
    return width * mix(1.0, max(PencilNearWidth, 1.0), clamp(PencilGrain, 0.0, 1.0))
        * widthAtDistance(distanceToEye);
}
