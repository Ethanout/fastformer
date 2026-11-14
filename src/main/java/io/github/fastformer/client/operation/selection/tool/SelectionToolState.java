package io.github.fastformer.client.operation.selection.tool;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.fastformer.common.selection.OperationSelectionMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Immutable tool draft. Parameters belong to the tool and survive undo and disk round trips. */
public record SelectionToolState(ResourceLocation toolId, OperationSelectionMode geometry, List<BlockPos> points,
   int prismBaseCount, BlockPos minPoint, BlockPos maxPoint, boolean secondPointOnly, Map<String, String> parameters) {
   public SelectionToolState {
      Objects.requireNonNull(toolId, "toolId");
      Objects.requireNonNull(geometry, "geometry");
      points = points == null ? List.of() : points.stream().filter(Objects::nonNull).map(BlockPos::immutable).toList();
      if (points.size() > 65_536) throw new IllegalArgumentException("Too many selection tool points");
      prismBaseCount = Math.max(0, prismBaseCount);
      minPoint = minPoint == null ? null : minPoint.immutable();
      maxPoint = maxPoint == null ? null : maxPoint.immutable();
      parameters = Map.copyOf(parameters);
      if (secondPointOnly && (geometry != OperationSelectionMode.CUBOID || points.size() != 1)) {
         throw new IllegalArgumentException("A second-only draft requires one cuboid point");
      }
   }

   public static MapCodec<SelectionToolState> codec(ResourceLocation id, OperationSelectionMode geometry) {
      return RecordCodecBuilder.mapCodec(instance -> instance.group(
         BlockPos.CODEC.listOf().fieldOf("points").forGetter(SelectionToolState::points),
         Codec.intRange(0, 65_536).fieldOf("base_count").forGetter(SelectionToolState::prismBaseCount),
         BlockPos.CODEC.optionalFieldOf("min").forGetter(state -> Optional.ofNullable(state.minPoint())),
         BlockPos.CODEC.optionalFieldOf("max").forGetter(state -> Optional.ofNullable(state.maxPoint())),
         Codec.BOOL.fieldOf("second_only").forGetter(SelectionToolState::secondPointOnly),
         Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("parameters").forGetter(SelectionToolState::parameters)
      ).apply(instance, (points, base, min, max, second, parameters) ->
         new SelectionToolState(id, geometry, points, base, min.orElse(null), max.orElse(null), second, parameters)));
   }

   public SelectionToolState withPoints(List<BlockPos> next, int base, boolean secondOnly) {
      BlockPos min = null, max = null;
      for (BlockPos point : next) {
         min = min == null ? point : min(min, point);
         max = max == null ? point : max(max, point);
      }
      return new SelectionToolState(toolId, geometry, next, base, min, max, secondOnly, parameters);
   }

   public SelectionToolState withBounds(BlockPos min, BlockPos max) {
      if (min == null || max == null || points.size() < 2) return this;
      return new SelectionToolState(toolId, geometry, points, prismBaseCount, min(min, max), max(min, max), secondPointOnly, parameters);
   }

   public SelectionToolState append(BlockPos point) {
      var next = new ArrayList<>(points);
      next.add(point);
      return withPoints(next, prismBaseCount, false);
   }

   public SelectionToolState removeLast(boolean reopenBase) {
      if (points.isEmpty()) return this;
      var next = points.subList(0, points.size() - 1);
      return withPoints(next, reopenBase && next.size() < prismBaseCount ? 0 : prismBaseCount, false);
   }

   public SelectionToolState expand(BlockPos point) {
      if (point == null || points.size() < 2) return this;
      BlockPos min = min(minPoint, point), max = max(maxPoint, point);
      return min.equals(minPoint) && max.equals(maxPoint) ? this : withBounds(min, max);
   }

   public BlockPos firstPoint() { return points.isEmpty() || secondPointOnly ? null : points.getFirst(); }
   public BlockPos secondPoint() { return secondPointOnly ? points.getFirst() : points.size() < 2 ? null : points.get(1); }
   private static BlockPos min(BlockPos a, BlockPos b) {
      return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
   }
   private static BlockPos max(BlockPos a, BlockPos b) {
      return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
   }
}
