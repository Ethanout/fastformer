package io.github.fastformer.server.task.block;

import io.github.fastformer.common.selection.OperationMode;
import io.github.fastformer.common.selection.OperationStackRegion;
import io.github.fastformer.common.selection.geometry.OperationGeometry;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.common.workspace.submission.SourceClearingRules;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/** Owns bounded scan and repetition cursors; it never reads or changes the world. */
final class SelectionPlacementPlan {
   private final AABB bounds;
   private final OperationMode mode;
   private final boolean copy;
   private final BlockPos translation;
   private final OperationStackRegion region;
   private int x;
   private int y;
   private int z;
   private int sourceIndex;
   private int repeatX;
   private int repeatY;
   private int repeatZ;

   SelectionPlacementPlan(AABB bounds, OperationMode mode, boolean copy, BlockPos translation, OperationStackRegion region) {
      this.bounds = bounds;
      this.mode = mode;
      this.copy = copy;
      this.translation = translation;
      this.region = region;
      resetScan();
      resetTargets();
   }

   BlockPos scanPosition() { return new BlockPos(x, y, z); }

   boolean advanceScan() {
      if (++z < Mth.ceil(bounds.maxZ)) return true;
      z = Mth.floor(bounds.minZ);
      if (++y < Mth.ceil(bounds.maxY)) return true;
      y = Mth.floor(bounds.minY);
      if (++x < Mth.ceil(bounds.maxX)) return true;
      resetScan();
      return false;
   }

   Target nextTarget(List<ReversibleBlockSnapshot> source) {
      while (repeatX <= region.max().getX()) {
         if (skipOrigin() && repeatX == 0 && repeatY == 0 && repeatZ == 0) {
            if (!advanceRepetition()) return null;
            continue;
         }
         ReversibleBlockSnapshot snapshot = source.get(sourceIndex++);
         BlockPos target = snapshot.pos().offset(displacement(new BlockPos(repeatX, repeatY, repeatZ)));
         if (sourceIndex >= source.size()) { sourceIndex = 0; advanceRepetition(); }
         return new Target(target, snapshot);
      }
      return null;
   }

   void resetTargets() {
      sourceIndex = 0;
      repeatX = region.min().getX();
      repeatY = region.min().getY();
      repeatZ = region.min().getZ();
   }

   boolean clearsSource() { return SourceClearingRules.clearsSource(mode, translation, copy); }
   long repetitionCount() { return skipOrigin() ? Math.max(0L, region.cellCount() - 1L) : region.cellCount(); }

   void visitTargets(List<ReversibleBlockSnapshot> source, int maxPlacement,
      BiConsumer<Integer, BlockPos> visitor) {
      for (BlockPos repetition : region.repetitions(maxPlacement)) {
         if (skipOrigin() && repetition.equals(BlockPos.ZERO)) continue;
         BlockPos displacement = displacement(repetition);
         for (int index = 0; index < source.size(); index++) visitor.accept(index, source.get(index).pos().offset(displacement));
      }
   }

   private void resetScan() { x = Mth.floor(bounds.minX); y = Mth.floor(bounds.minY); z = Mth.floor(bounds.minZ); }
   private boolean skipOrigin() { return mode == OperationMode.STACK && translation.equals(BlockPos.ZERO); }
   private BlockPos displacement(BlockPos repetition) { return OperationGeometry.stackDisplacement(bounds, repetition).offset(translation); }

   private boolean advanceRepetition() {
      if (++repeatZ <= region.max().getZ()) return true;
      repeatZ = region.min().getZ();
      if (++repeatY <= region.max().getY()) return true;
      repeatY = region.min().getY();
      return ++repeatX <= region.max().getX();
   }

   record Target(BlockPos pos, ReversibleBlockSnapshot source) {}
}
