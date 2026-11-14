package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.geometry.GeometryMode;

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
