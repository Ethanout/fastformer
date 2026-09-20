package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;

/** Groups the independent point buffers used by the operation selection modes. */
final class OperationSelectionPoints {
   private final SelectionPointState cuboid = new SelectionPointState();
   private final SelectionPointState prism = new SelectionPointState();
   private final SelectionPointState hull = new SelectionPointState();

   SelectionPointState forMode(OperationSelectionMode mode) {
      return switch (mode) {
         case CUBOID -> this.cuboid;
         case PRISM -> this.prism;
         case CONVEX_HULL -> this.hull;
      };
   }

   SelectionPointState cuboid() {
      return this.cuboid;
   }

   SelectionPointState prism() {
      return this.prism;
   }

   SelectionPointState hull() {
      return this.hull;
   }

   void clear() {
      this.cuboid.clear();
      this.prism.clear();
      this.hull.clear();
   }
}
