package io.github.fastformer.client.render.interaction;

import io.github.fastformer.fastplace.geometry.OperationGeometry;
import net.minecraft.core.BlockPos;

/** Immutable result of resolving an operation pointer against world and selection targets. */
public record OperationPointerTarget(
   OperationPointerKind kind,
   BlockPos block,
   OperationGeometry.RayHit face,
   double distance
) {
   public static OperationPointerTarget world(BlockPos block, double distance) {
      return new OperationPointerTarget(OperationPointerKind.WORLD, block, null, distance);
   }

   public static OperationPointerTarget face(OperationGeometry.RayHit face) {
      return new OperationPointerTarget(OperationPointerKind.FACE, null, face, face.distance());
   }

   public static OperationPointerTarget none() {
      return new OperationPointerTarget(OperationPointerKind.NONE, null, null, Double.POSITIVE_INFINITY);
   }
}
