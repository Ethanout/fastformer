package io.github.fastformer.common.geometry.session;

import io.github.fastformer.common.geometry.GeometryMode;
import net.minecraft.core.BlockPos;

public final class WallGeometryState extends GeometryShapeState {
   BlockPos extrusion = BlockPos.ZERO;

   WallGeometryState() {
      super(GeometryMode.WALL);
   }

   @Override
   void afterPointRemoved() {
      this.extrusion = BlockPos.ZERO;
   }

   public BlockPos extrusion() {
      return this.extrusion;
   }

   public void adjustExtrusion(BlockPos step) {
      if (this.closed()) {
         this.extrusion = new BlockPos(
            Math.clamp(this.extrusion.getX() + step.getX(), -128, 128),
            Math.clamp(this.extrusion.getY() + step.getY(), -128, 128),
            Math.clamp(this.extrusion.getZ() + step.getZ(), -128, 128)
         );
      }
   }
}
