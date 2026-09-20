package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.GeometryMode;
import net.minecraft.core.BlockPos;

final class WallGeometryState extends GeometryShapeState {
   BlockPos extrusion = BlockPos.ZERO;

   WallGeometryState() {
      super(GeometryMode.WALL);
   }

   @Override
   void afterPointRemoved() {
      this.extrusion = BlockPos.ZERO;
   }
}

