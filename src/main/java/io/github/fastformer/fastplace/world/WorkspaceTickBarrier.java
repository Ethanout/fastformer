package io.github.fastformer.fastplace.world;

import io.github.fastformer.fastplace.interaction.ProtectedInteractionTick;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

/** Defers natural activity near a staged workspace until its transaction finishes. */
public final class WorkspaceTickBarrier implements AutoCloseable {
   private static final Map<Object, WorkspaceTickBarrier> ACTIVE = new IdentityHashMap<>();
   private final ServerLevel level;
   private final LongOpenHashSet chunks = new LongOpenHashSet();

   public WorkspaceTickBarrier(ServerLevel level, Iterable<BlockPos> positions) {
      this.level = level;
      // Include adjacent chunks so water outside the edit cannot flow into a half-written scene.
      for (BlockPos pos : positions) {
         int x = pos.getX() >> 4;
         int z = pos.getZ() >> 4;
         for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) chunks.add(ChunkPos.asLong(x + dx, z + dz));
      }
      if (ACTIVE.containsKey(level)) throw new IllegalStateException("A workspace already owns this level's tick barrier");
      ACTIVE.put(level, this);
      ACTIVE.put(level.getBlockTicks(), this);
      ACTIVE.put(level.getFluidTicks(), this);
   }

   public static boolean pausesRandomTick(ServerLevel level, BlockPos pos) {
      var barrier = ACTIVE.get(level);
      return barrier != null && barrier.chunks.contains(ChunkPos.asLong(pos));
   }

   public static <T> boolean defer(LevelTicks<T> ticks, ScheduledTick<T> tick) {
      var barrier = ACTIVE.get(ticks);
      if (barrier == null || !barrier.chunks.contains(ChunkPos.asLong(tick.pos()))) return false;
      var later = new ScheduledTick<>(tick.type(), tick.pos(), barrier.level.getGameTime() + 1,
         tick.priority(), tick.subTickOrder());
      ((ProtectedInteractionTick)(Object)later).fastformer$suppressNeighbors(
         ((ProtectedInteractionTick)(Object)tick).fastformer$suppressesNeighbors());
      ticks.schedule(later);
      return true;
   }

   @Override public void close() {
      ACTIVE.remove(level, this);
      ACTIVE.remove(level.getBlockTicks(), this);
      ACTIVE.remove(level.getFluidTicks(), this);
   }
}
