package io.github.fastformer.fastplace.selection;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** A bounded flood fill. Callers supply one seed-relative match rule and a tick budget. */
public final class SmartSelectionSearch {
   public static final int MAX_BLOCKS = 65_536;
   private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
   private final Set<BlockPos> visited = new HashSet<>();
   private final Set<BlockPos> result = new LinkedHashSet<>();
   private final int limit;

   public SmartSelectionSearch(BlockPos seed) { this(seed, MAX_BLOCKS); }
   public SmartSelectionSearch(BlockPos seed, int limit) {
      if (limit < 1) throw new IllegalArgumentException("A positive search limit is required");
      this.limit = limit;
      queue.add(seed.immutable());
      visited.add(seed.immutable());
   }

   public boolean step(Predicate<BlockPos> matches, int budget, long deadline) {
      for (int scanned = 0; scanned < budget && !queue.isEmpty() && System.nanoTime() < deadline; scanned++) {
         BlockPos position = queue.removeFirst();
         if (!matches.test(position)) continue;
         if (result.size() >= limit) throw new LimitExceeded();
         result.add(position);
         for (Direction direction : Direction.values()) {
            BlockPos neighbor = position.relative(direction);
            if (visited.add(neighbor)) queue.addLast(neighbor);
         }
      }
      return queue.isEmpty();
   }

   public Set<BlockPos> result() { return Set.copyOf(result); }
   public int size() { return result.size(); }
   public static final class LimitExceeded extends RuntimeException { }
}
