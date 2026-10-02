package io.github.fastformer.workspace.preview;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/** Composes layers in creation order. Slot IDs identify layers but do not order them. */
public final class WorkspaceScene<T> {
   private final Predicate<T> visible;
   private final Map<BlockPos, T> blocks = new LinkedHashMap<>();
   private final Map<BlockPos, Integer> owners = new LinkedHashMap<>();
   private final Set<BlockPos> sources = new LinkedHashSet<>();

   public WorkspaceScene(Predicate<T> visible) { this.visible = visible; }

   public void clearSources(Iterable<BlockPos> positions) {
      positions.forEach(pos -> sources.add(pos.immutable()));
   }

   public void overlay(int id, Map<BlockPos, T> layer) {
      layer.forEach((pos, value) -> {
         if (visible.test(value)) {
            blocks.put(pos.immutable(), value);
            owners.put(pos.immutable(), id);
         }
      });
   }

   public Map<BlockPos, T> blocks() { return Collections.unmodifiableMap(blocks); }
   public Map<BlockPos, Integer> owners() { return Collections.unmodifiableMap(owners); }
   public Set<BlockPos> sources() { return Collections.unmodifiableSet(sources); }

   public Map<BlockPos, T> desired(T empty) {
      Map<BlockPos, T> result = new LinkedHashMap<>();
      sources.forEach(pos -> result.put(pos, empty));
      result.putAll(blocks);
      return result;
   }

   /** Remaining source claims stay in the world until those layers are committed. */
   public Set<BlockPos> exclusiveSources(Set<BlockPos> retainedSources) {
      Set<BlockPos> result = new LinkedHashSet<>(sources);
      result.removeAll(retainedSources);
      return result;
   }
}
