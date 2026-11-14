package io.github.fastformer.common.geometry.session;

import io.github.fastformer.common.geometry.GeometryMode;

public final class CompoundGeometryState extends GeometryShapeState {
   int shapeVariant;

   CompoundGeometryState() {
      super(GeometryMode.COMPOUND);
   }

   public int compoundShapeVariant() {
      return this.shapeVariant;
   }

   public void cycleCompoundShapeVariant(int steps) {
      this.shapeVariant = Math.floorMod(this.shapeVariant + Integer.signum(steps), 2);
   }
}
