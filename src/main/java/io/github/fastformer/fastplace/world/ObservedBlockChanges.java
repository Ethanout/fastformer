package io.github.fastformer.fastplace.world;

import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Captures first writes, then reads final states after placement callbacks and event cancellation. */
final class ObservedBlockChanges implements AutoCloseable {
   private static final ThreadLocal<ObservedBlockChanges> ACTIVE = new ThreadLocal<>();
   private final ServerLevel level;
   private final LinkedHashMap<BlockPos, ReversibleBlockSnapshot> originals = new LinkedHashMap<>();
   private final Map<BlockPos, ReversibleBlockSnapshot> after = new LinkedHashMap<>();
   private boolean failed;

   ObservedBlockChanges(ServerLevel level) {
      this.level = level;
      ACTIVE.set(this);
   }

   static boolean active() { return ACTIVE.get() != null; }

   static boolean observe(Level level, BlockPos pos, BooleanSupplier write) {
      var capture = ACTIVE.get();
      if (capture == null || capture.level != level || WorldWriteSideEffectGuard.writing(level, pos)) {
         return write.getAsBoolean();
      }
      BlockPos position = pos.immutable();
      if (!capture.originals.containsKey(position)) {
         var before = ReversibleBlockSnapshot.capture(capture.level, position);
         if (before.isEmpty()) {
            capture.failed = true;
            return false;
         }
         capture.originals.put(position, before.orElseThrow());
      }
      return write.getAsBoolean();
   }

   boolean finish() {
      for (var entry : originals.entrySet()) {
         var current = ReversibleBlockSnapshot.capture(level, entry.getKey());
         if (current.isEmpty()) failed = true;
         else if (!entry.getValue().sameContents(current.orElseThrow())) after.put(entry.getKey(), current.orElseThrow());
      }
      return !failed;
   }

   ArrayDeque<ReversibleBlockSnapshot> changedBefore() {
      ArrayDeque<ReversibleBlockSnapshot> result = new ArrayDeque<>();
      for (BlockPos pos : after.keySet()) result.addFirst(originals.get(pos));
      return result;
   }

   ArrayDeque<ReversibleBlockSnapshot> rollbackSnapshots() { return new ArrayDeque<>(originals.reversed().values()); }
   Map<BlockPos, ReversibleBlockSnapshot> after() { return after; }

   @Override public void close() { ACTIVE.remove(); }
}
