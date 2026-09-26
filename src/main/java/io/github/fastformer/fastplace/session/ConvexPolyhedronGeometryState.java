package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.geometry.GeometryMode;

final class ConvexPolyhedronGeometryState extends GeometryShapeState {
   int selectedPointIndex = -1;

   ConvexPolyhedronGeometryState() {
      super(GeometryMode.CONVEX_POLYHEDRON);
   }
}
