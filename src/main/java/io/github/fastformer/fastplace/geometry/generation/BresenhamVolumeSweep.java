package io.github.fastformer.fastplace.geometry.generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class BresenhamVolumeSweep {
   private BresenhamVolumeSweep() {
   }

   static Result generate(
      Set<BlockPos> face,
      Set<BlockPos> boundary,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (face == null || face.isEmpty() || maxBlocks <= 0) {
         return new Result(Set.of(), true);
      }
      BlockPos start = BlockPos.ZERO;
      BlockPos end = BlockPos.containing(extrusion);
      List<BlockPos> offsets = directedPath(start, end);
      int[] transitionOrder = LineGenerator.axesByDescendingSlope(start, end);
      if (offsets.isEmpty()) {
         return new Result(Set.of(), true);
      }
      Set<BlockPos> result = new ObservedBlockSet(observer);
      if (boundary != null) {
         for (BlockPos boundaryPoint : boundary) {
            if (!face.contains(boundaryPoint)) {
               continue;
            }
            for (BlockPos offset : offsets) {
               result.add(boundaryPoint.offset(offset));
               if (result.size() >= maxBlocks) {
                  return new Result(result, false);
               }
            }
         }
      }
      if (!addTranslatedSlice(result, face, offsets.getFirst(), maxBlocks)) {
         return new Result(result, false);
      }
      for (int index = 1; index < offsets.size(); index++) {
         BlockPos previous = offsets.get(index - 1);
         BlockPos current = offsets.get(index);
         if (!addIntermediateSlices(result, face, previous, current, transitionOrder, maxBlocks)
            || !addTranslatedSlice(result, face, current, maxBlocks)) {
            return new Result(result, false);
         }
      }
      return new Result(result, true);
   }

   private static boolean addTranslatedSlice(
      Set<BlockPos> result, Set<BlockPos> face, BlockPos offset, int maxBlocks
   ) {
      for (BlockPos position : face) {
         result.add(position.offset(offset));
         if (result.size() >= maxBlocks) {
            return false;
         }
      }
      return true;
   }

   private static boolean addIntermediateSlices(
      Set<BlockPos> result,
      Set<BlockPos> face,
      BlockPos previousOffset,
      BlockPos currentOffset,
      int[] transitionOrder,
      int maxBlocks
   ) {
      BlockPos delta = currentOffset.subtract(previousOffset);
      int changedAxes = Integer.signum(Math.abs(delta.getX()))
         + Integer.signum(Math.abs(delta.getY()))
         + Integer.signum(Math.abs(delta.getZ()));
      if (changedAxes <= 1) {
         return true;
      }
      int[] coordinates = {previousOffset.getX(), previousOffset.getY(), previousOffset.getZ()};
      int[] target = {currentOffset.getX(), currentOffset.getY(), currentOffset.getZ()};
      for (int axis : transitionOrder) {
         if (coordinates[axis] == target[axis]) {
            continue;
         }
         coordinates[axis] = target[axis];
         BlockPos intermediate = new BlockPos(coordinates[0], coordinates[1], coordinates[2]);
         if (!intermediate.equals(currentOffset)
            && !addTranslatedSlice(result, face, intermediate, maxBlocks)) {
            return false;
         }
      }
      return true;
   }

   private static List<BlockPos> directedPath(BlockPos start, BlockPos end) {
      ArrayList<BlockPos> result = new ArrayList<>(LineGenerator.path(start, end));
      if (!result.isEmpty() && !result.getFirst().equals(start)) {
         Collections.reverse(result);
      }
      return List.copyOf(result);
   }

   record Result(Set<BlockPos> blocks, boolean complete) {
   }
}
