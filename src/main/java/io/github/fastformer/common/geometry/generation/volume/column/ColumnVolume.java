package io.github.fastformer.common.geometry.generation.volume.column;

import static io.github.fastformer.common.geometry.generation.volume.column.ColumnCoordinates.coordinate;
import io.github.fastformer.common.geometry.generation.source.GenerationLimitExceeded;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.core.math.SaturatedArithmetic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Compact column-convex volume bounded by independently authored surface samples. */
public final class ColumnVolume {
   private final int axis;
   private final int firstKeyAxis;
   private final int secondKeyAxis;
   private final BlockGenerationObserver observer;
   private final LinkedHashSet<BlockPos> shell = new LinkedHashSet<>();
   private final LinkedHashSet<BlockPos> overlays = new LinkedHashSet<>();
   private final Map<ColumnKey, ColumnSpan> spans = new LinkedHashMap<>();
   private long blockCount;

   ColumnVolume(int axis, BlockGenerationObserver observer) {
      if (axis < 0 || axis > 2) throw new IllegalArgumentException("axis " + axis);
      this.axis = axis;
      this.firstKeyAxis = (axis + 1) % 3;
      this.secondKeyAxis = (axis + 2) % 3;
      this.observer = observer == null ? BlockGenerationObserver.NONE : observer;
   }

   /** Samples own the column endpoints; no analytic solid extends the surface. */
   public static ColumnVolume fromShell(Set<BlockPos> shell, int axis, BlockGenerationObserver observer) {
      ColumnVolume volume = new ColumnVolume(axis, observer);
      shell.forEach(block -> volume.addSeed(block, true));
      return volume;
   }

   void addTarget(BlockPos seed) {
      this.addSeed(seed, false);
   }

   void addShell(BlockPos seed) {
      this.observer.checkCancelled();
      this.observer.onScanned(1L);
      BlockPos immutable = seed.immutable();
      this.shell.add(immutable);
      ColumnKey key = this.key(immutable);
      int value = coordinate(immutable, this.axis);
      ColumnSpan target = this.spans.get(key);
      if (target == null) {
         this.overlays.add(immutable);
         return;
      }
      this.extend(key, target, value);
   }

   public long blockCount() {
      return SaturatedArithmetic.saturatedAdd(this.blockCount, this.overlays.size());
   }

   private void addSeed(BlockPos seed, boolean ownedByShell) {
      this.observer.checkCancelled();
      this.observer.onScanned(1L);
      BlockPos immutable = seed.immutable();
      if (ownedByShell) this.shell.add(immutable);
      ColumnKey key = this.key(immutable);
      int value = coordinate(immutable, this.axis);
      ColumnSpan previous = this.spans.get(key);
      if (previous == null) {
         this.spans.put(key, new ColumnSpan(value, value));
         this.blockCount = SaturatedArithmetic.saturatedAdd(this.blockCount, 1L);
         return;
      }
      this.extend(key, previous, value);
   }

   private void extend(ColumnKey key, ColumnSpan previous, int value) {
      long added = value < previous.minimum() ? (long)previous.minimum() - value
         : value > previous.maximum() ? (long)value - previous.maximum() : 0L;
      if (added > 0L) {
         this.spans.put(key, previous.include(value));
         this.blockCount = SaturatedArithmetic.saturatedAdd(this.blockCount, added);
         if (added > 1L) this.observer.onScanned(added - 1L);
      }
   }

   private ColumnKey key(BlockPos position) {
      return new ColumnKey(coordinate(position, this.firstKeyAxis), coordinate(position, this.secondKeyAxis));
   }

   private BlockPos position(ColumnKey key, int coordinate) {
      int[] values = new int[3];
      values[this.axis] = coordinate;
      values[this.firstKeyAxis] = key.first();
      values[this.secondKeyAxis] = key.second();
      return new BlockPos(values[0], values[1], values[2]);
   }

   public long fiveNeighborOpenings() {
      HashSet<BlockPos> overlayNeighborhood = new HashSet<>();
      for (BlockPos overlay : this.overlays) {
         for (int[] step : ColumnCoordinates.FACE_STEPS) {
            BlockPos neighbor = ColumnCoordinates.neighbor(overlay, step);
            if (neighbor != null) overlayNeighborhood.add(neighbor);
         }
      }
      long result = 0L;
      for (BlockPos candidate : overlayNeighborhood) {
         if (this.isFiveNeighborOpening(candidate)) result = SaturatedArithmetic.saturatedAdd(result, 1L);
      }
      for (Map.Entry<ColumnKey, ColumnSpan> entry : this.spans.entrySet()) {
         this.observer.checkCancelled();
         ColumnSpan span = entry.getValue();
         long below = (long)span.minimum() - 1L;
         long above = (long)span.maximum() + 1L;
         if (below >= Integer.MIN_VALUE) {
            BlockPos candidate = this.position(entry.getKey(), (int)below);
            if (!overlayNeighborhood.contains(candidate) && this.isFiveNeighborOpening(candidate)) {
               result = SaturatedArithmetic.saturatedAdd(result, 1L);
            }
         }
         if (above <= Integer.MAX_VALUE) {
            BlockPos candidate = this.position(entry.getKey(), (int)above);
            if (!overlayNeighborhood.contains(candidate) && this.isFiveNeighborOpening(candidate)) {
               result = SaturatedArithmetic.saturatedAdd(result, 1L);
            }
         }
      }
      return result;
   }

   public boolean hasEnclosedAir() {
      LinkedHashMap<ColumnKey, ArrayList<ColumnSpan>> raw = new LinkedHashMap<>();
      this.spans.forEach((key, span) -> raw.computeIfAbsent(key, ignored -> new ArrayList<>()).add(span));
      for (BlockPos overlay : this.overlays) {
         this.observer.checkCancelled();
         int value = coordinate(overlay, this.axis);
         raw.computeIfAbsent(this.key(overlay), ignored -> new ArrayList<>()).add(new ColumnSpan(value, value));
      }
      return ColumnAirModel.create(raw, this.observer).hasEnclosedAir(this.observer);
   }

   private boolean isFiveNeighborOpening(BlockPos candidate) {
      if (this.contains(candidate)) return false;
      int occupied = 0;
      for (int[] step : ColumnCoordinates.FACE_STEPS) {
         BlockPos neighbor = ColumnCoordinates.neighbor(candidate, step);
         if (neighbor != null && this.contains(neighbor)) occupied++;
      }
      return occupied == 5;
   }

   private boolean contains(BlockPos position) {
      return this.overlays.contains(position) || this.coveredBySpan(position);
   }

   private boolean coveredBySpan(BlockPos position) {
      ColumnSpan span = this.spans.get(this.key(position));
      return span != null && span.contains(coordinate(position, this.axis));
   }

   public Result solidResult(int maxBlocks, BlockGenerationObserver observer) {
      this.overlays.removeIf(this::coveredBySpan);
      if (this.blockCount() > maxBlocks) return limitResult(maxBlocks, observer);
      return new Result(this.solid(), true, Collections.unmodifiableSet(this.shell));
   }

   public Result boundaryResult(int maxBlocks, BlockGenerationObserver observer, boolean preserveFaces) {
      this.overlays.removeIf(this::coveredBySpan);
      BoundaryColumnBlockSet blocks = BoundaryColumnBlockSet.create(this.solid(), maxBlocks, this.observer);
      if (blocks == null) return limitResult(maxBlocks, observer);
      if (!preserveFaces) return new Result(blocks, true, Set.of());
      // Authored edge seams may contain inner samples; retain them in hollow mode.
      Set<BlockPos> surface = new LinkedHashSet<>(this.shell);
      if (surface.size() > maxBlocks) return limitResult(maxBlocks, observer);
      for (BlockPos block : blocks) {
         observer.checkCancelled();
         surface.add(block);
         if (surface.size() > maxBlocks) return limitResult(maxBlocks, observer);
      }
      return new Result(Collections.unmodifiableSet(surface), true, Collections.unmodifiableSet(this.shell));
   }

   private ColumnBlockSet solid() {
      return new ColumnBlockSet(this.axis, this.spans, this.overlays, this.blockCount());
   }

   private static Result limitResult(int maxBlocks, BlockGenerationObserver observer) {
      return new Result(GenerationLimitExceeded.witness(maxBlocks, observer), false, Set.of());
   }

   /** A staged result emits no generated-block callbacks until its caller publishes it. */
   public record Result(Set<BlockPos> blocks, boolean complete, Set<BlockPos> shell) {}
}
