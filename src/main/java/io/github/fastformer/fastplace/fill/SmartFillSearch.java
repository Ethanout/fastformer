package io.github.fastformer.fastplace.fill;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Read-only, single-layer surface detection. Search limits never count as solid boundaries. */
public final class SmartFillSearch {
   public static final int MAX_TARGETS = 1024;
   private static final int MAX_READS = 65_536;
   public enum Cell { AIR, SOLID, UNKNOWN }
   @FunctionalInterface public interface Cells { Cell read(BlockPos position); }
   public record Plan(Set<BlockPos> targets) {
      public Plan { targets = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(targets)); }
   }
   private final Cells source;
   private final Map<BlockPos, Cell> cache = new HashMap<>();

   public SmartFillSearch(Cells source) { this.source = source; }

   /** All three planes share the hit-point seed; prefer the hit face when several are bounded. */
   public Plan find(BlockPos seed, Direction face) {
      if (cell(seed) != Cell.AIR) return null;
      Plan preferred = null;
      for (Direction.Axis axis : Direction.Axis.values()) {
         Set<BlockPos> targets = opening(seed, axis);
         if (targets == null) continue;
         if (preferred == null || axis == face.getAxis()) preferred = new Plan(targets);
      }
      return preferred;
   }

   private Cell cell(BlockPos position) {
      Cell known = cache.get(position);
      if (known != null) return known;
      if (cache.size() >= MAX_READS) return Cell.UNKNOWN;
      Cell value = source.read(position);
      cache.put(position.immutable(), value);
      return value;
   }

   private Set<BlockPos> opening(BlockPos seed, Direction.Axis axis) {
      if (!hasRimInAllDirections(seed, axis)) return null;
      Set<BlockPos> cells = new LinkedHashSet<>();
      ArrayDeque<BlockPos> queue = new ArrayDeque<>();
      cells.add(seed);
      queue.add(seed);
      while (!queue.isEmpty()) {
         BlockPos position = queue.removeFirst();
         for (Direction side : Direction.values()) {
            if (side.getAxis() == axis) continue;
            BlockPos next = position.relative(side);
            Cell value = cell(next);
            if (value == Cell.UNKNOWN) return null;
            if (value == Cell.SOLID) continue;
            if (next.distManhattan(seed) > 32 || cells.size() >= MAX_TARGETS && !cells.contains(next)) return null;
            if (cells.add(next)) queue.add(next);
         }
      }
      return cells;
   }

   private boolean hasRimInAllDirections(BlockPos seed, Direction.Axis axis) {
      for (Direction side : Direction.values()) {
         if (side.getAxis() == axis) continue;
         boolean bounded = false;
         for (int distance = 1; distance <= 32; distance++) {
            Cell value = cell(seed.relative(side, distance));
            if (value == Cell.UNKNOWN) return false;
            if (value == Cell.SOLID) { bounded = true; break; }
         }
         if (!bounded) return false;
      }
      return true;
   }

}
