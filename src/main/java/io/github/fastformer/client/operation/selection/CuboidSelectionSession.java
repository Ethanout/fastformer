package io.github.fastformer.client.operation.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** Client-side AABB selection editing state used while a selection session is active. */
public final class CuboidSelectionSession {
   public enum Editability { FREE, LOCKED }

   public enum Face {
      X_POSITIVE, X_NEGATIVE, Y_POSITIVE, Y_NEGATIVE, Z_POSITIVE, Z_NEGATIVE
   }

   private BlockPos point1;
   private BlockPos point2;
   private BlockPos minPoint;
   private BlockPos maxPoint;
   private Editability editability = Editability.FREE;

   public CuboidSelectionSession(BlockPos point1, BlockPos point2) {
      setPointsInternal(point1, point2);
   }

   public BlockPos point1() { return this.point1; }
   public BlockPos point2() { return this.point2; }
   public BlockPos minPoint() { return this.minPoint; }
   public BlockPos maxPoint() { return this.maxPoint; }
   public Editability editability() { return this.editability; }
   public boolean editable() { return this.editability == Editability.FREE; }

   public void lock() { this.editability = Editability.LOCKED; }
   public void unlock() { this.editability = Editability.FREE; }

   public boolean setPoint1(BlockPos value) {
      if (!editable() || value == null) return false;
      setPointsInternal(value, this.point2);
      return true;
   }

   public boolean setPoint2(BlockPos value) {
      if (!editable() || value == null) return false;
      setPointsInternal(this.point1, value);
      return true;
   }

   /** Moves one fixed face. Negative amounts are allowed but never invert the AABB. */
   public boolean pushPull(Face face, int amount) {
      if (!editable() || face == null || amount == 0) return false;
      int minX = this.minPoint.getX(), minY = this.minPoint.getY(), minZ = this.minPoint.getZ();
      int maxX = this.maxPoint.getX(), maxY = this.maxPoint.getY(), maxZ = this.maxPoint.getZ();
      switch (face) {
         case X_POSITIVE -> maxX = Math.max(minX, maxX + amount);
         case X_NEGATIVE -> minX = Math.min(maxX, minX - amount);
         case Y_POSITIVE -> maxY = Math.max(minY, maxY + amount);
         case Y_NEGATIVE -> minY = Math.min(maxY, minY - amount);
         case Z_POSITIVE -> maxZ = Math.max(minZ, maxZ + amount);
         case Z_NEGATIVE -> minZ = Math.min(maxZ, minZ - amount);
      }
      BlockPos nextMin = new BlockPos(minX, minY, minZ);
      BlockPos nextMax = new BlockPos(maxX, maxY, maxZ);
      if (nextMin.equals(this.minPoint) && nextMax.equals(this.maxPoint)) return false;
      this.minPoint = nextMin;
      this.maxPoint = nextMax;
      return true;
   }

   /** Expands the current AABB to contain the point. This operation never shrinks it. */
   public boolean expandTo(BlockPos value) {
      if (!editable() || value == null) return false;
      BlockPos nextMin = new BlockPos(
         Math.min(this.minPoint.getX(), value.getX()),
         Math.min(this.minPoint.getY(), value.getY()),
         Math.min(this.minPoint.getZ(), value.getZ())
      );
      BlockPos nextMax = new BlockPos(
         Math.max(this.maxPoint.getX(), value.getX()),
         Math.max(this.maxPoint.getY(), value.getY()),
         Math.max(this.maxPoint.getZ(), value.getZ())
      );
      if (nextMin.equals(this.minPoint) && nextMax.equals(this.maxPoint)) return false;
      this.minPoint = nextMin;
      this.maxPoint = nextMax;
      return true;
   }

   /** Compatibility alias for callers that use the shorter name. */
   public boolean expand(BlockPos value) {
      return expandTo(value);
   }

   public AABB bounds() {
      return new AABB(
         this.minPoint.getX(), this.minPoint.getY(), this.minPoint.getZ(),
         this.maxPoint.getX() + 1.0, this.maxPoint.getY() + 1.0, this.maxPoint.getZ() + 1.0
      );
   }

   private void setPointsInternal(BlockPos first, BlockPos second) {
      this.point1 = first == null ? null : first.immutable();
      this.point2 = second == null ? null : second.immutable();
      if (this.point1 == null || this.point2 == null) {
         this.minPoint = this.point1 == null ? this.point2 : this.point1;
         this.maxPoint = this.minPoint;
         return;
      }
      this.minPoint = new BlockPos(
         Math.min(this.point1.getX(), this.point2.getX()),
         Math.min(this.point1.getY(), this.point2.getY()),
         Math.min(this.point1.getZ(), this.point2.getZ())
      );
      this.maxPoint = new BlockPos(
         Math.max(this.point1.getX(), this.point2.getX()),
         Math.max(this.point1.getY(), this.point2.getY()),
         Math.max(this.point1.getZ(), this.point2.getZ())
      );
   }
}
