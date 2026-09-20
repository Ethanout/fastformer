package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.GeometryMode;

final class CompoundGeometryState extends GeometryShapeState {
   int shapeVariant;

   CompoundGeometryState() {
      super(GeometryMode.COMPOUND);
   }
}

