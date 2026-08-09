package io.github.fastformer.client.operation;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Resolves workspace transforms into the exact voxel maps used by rendering and submission. */
public final class WorkspacePreviewComposer {
   private WorkspacePreviewComposer() {
   }

   public static <T> Map<BlockPos, T> resolveValues(Map<BlockPos, T> source, WorkspaceTransform transform) {
      if (source == null || source.isEmpty()) {
         return Map.of();
      }
      if (transform == null || transform.isIdentity()) {
         return Map.copyOf(source);
      }
      Map<BlockPos, T> scaled = scaleValues(source, transform.scale());
      OccupiedBlockBounds scaledBounds = OccupiedBlockBounds.from(scaled.keySet()).orElseThrow();
      LinkedHashMap<BlockPos, T> repeated = new LinkedHashMap<>();
      for (BlockPos repetition : transform.repeats().repetitions(Integer.MAX_VALUE)) {
         BlockPos stride = transform.repeatStride();
         BlockPos displacement = new BlockPos(
            repetition.getX() * (stride.getX() == 0 ? scaledBounds.width(AxisGizmo.Axis.X) : Math.abs(stride.getX())),
            repetition.getY() * (stride.getY() == 0 ? scaledBounds.width(AxisGizmo.Axis.Y) : Math.abs(stride.getY())),
            repetition.getZ() * (stride.getZ() == 0 ? scaledBounds.width(AxisGizmo.Axis.Z) : Math.abs(stride.getZ()))
         );
         scaled.forEach((pos, value) -> repeated.put(pos.offset(displacement), value));
      }

      Map<BlockPos, T> rotated = repeated;
      Vec3 rotation = transform.rotation();
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         double radians = axisComponent(rotation, axis);
         if (Math.abs(radians) > 1.0E-12 && !rotated.isEmpty()) {
            Vec3 pivot = OccupiedBlockBounds.from(rotated.keySet()).orElseThrow().center();
            rotated = VoxelRotation.rotateValues(rotated, pivot, axis, radians);
         }
      }

      LinkedHashMap<BlockPos, T> translated = new LinkedHashMap<>();
      Vec3 offset = transform.translation();
      rotated.forEach((pos, value) -> {
         Vec3 center = Vec3.atCenterOf(pos).add(offset);
         translated.put(BlockPos.containing(center.x, center.y, center.z), value);
      });
      return Map.copyOf(translated);
   }

   static <T> Map<BlockPos, T> scaleValues(Map<BlockPos, T> source, Vec3 scale) {
      if (source == null || source.isEmpty()) {
         return Map.of();
      }
      OccupiedBlockBounds bounds = OccupiedBlockBounds.from(source.keySet()).orElseThrow();
      int sourceX = bounds.width(AxisGizmo.Axis.X);
      int sourceY = bounds.width(AxisGizmo.Axis.Y);
      int sourceZ = bounds.width(AxisGizmo.Axis.Z);
      int targetX = Math.max(1, (int)Math.round(sourceX * scale.x));
      int targetY = Math.max(1, (int)Math.round(sourceY * scale.y));
      int targetZ = Math.max(1, (int)Math.round(sourceZ * scale.z));
      if (targetX == sourceX && targetY == sourceY && targetZ == sourceZ) {
         return source;
      }
      Vec3 center = bounds.center();
      int minX = (int)Math.floor(center.x - targetX * 0.5);
      int minY = (int)Math.floor(center.y - targetY * 0.5);
      int minZ = (int)Math.floor(center.z - targetZ * 0.5);
      LinkedHashMap<BlockPos, T> result = new LinkedHashMap<>();
      for (int x = 0; x < targetX; x++) {
         int sourceOffsetX = Math.min(sourceX - 1, (int)Math.floor((x + 0.5) * sourceX / targetX));
         for (int y = 0; y < targetY; y++) {
            int sourceOffsetY = Math.min(sourceY - 1, (int)Math.floor((y + 0.5) * sourceY / targetY));
            for (int z = 0; z < targetZ; z++) {
               int sourceOffsetZ = Math.min(sourceZ - 1, (int)Math.floor((z + 0.5) * sourceZ / targetZ));
               T value = source.get(new BlockPos(
                  bounds.min().getX() + sourceOffsetX,
                  bounds.min().getY() + sourceOffsetY,
                  bounds.min().getZ() + sourceOffsetZ
               ));
               if (value != null) {
                  result.put(new BlockPos(minX + x, minY + y, minZ + z), value);
               }
            }
         }
      }
      return Map.copyOf(result);
   }

   public static <T> ComposedValues<T> composeValues(Map<Integer, Map<BlockPos, T>> parts) {
      LinkedHashMap<BlockPos, T> result = new LinkedHashMap<>();
      LinkedHashSet<BlockPos> overlaps = new LinkedHashSet<>();
      List<Integer> ids = new ArrayList<>(parts.keySet());
      ids.sort(Comparator.naturalOrder());
      for (int id : ids) {
         parts.get(id).forEach((pos, value) -> {
            if (result.containsKey(pos)) {
               overlaps.add(pos.immutable());
            }
            result.put(pos.immutable(), value);
         });
      }
      return new ComposedValues<>(Map.copyOf(result), Set.copyOf(overlaps));
   }

   public static Map<BlockPos, ClientBlockSnapshot> resolve(ClientSelectionPart part) {
      return resolveValues(part.blocks(), part.transform());
   }

   private static double axisComponent(Vec3 value, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
   }

   public record ComposedValues<T>(Map<BlockPos, T> values, Set<BlockPos> overlaps) {
   }
}
