package io.github.fastformer.server.history.memory;

import io.github.fastformer.server.history.compaction.BlockHistoryCompactionRules;

import io.github.fastformer.server.history.compaction.HistoryCompactionPolicy;
import io.github.fastformer.common.history.HistoryCompressionLimits;
import io.github.fastformer.server.history.entry.BlockEditHistoryMetadata;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.entry.HistoryEntry;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.server.worldwrite.snapshot.BlockEntitySnapshot;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

class HistoryCompactionSchedulerTest {
   private static final HistoryCompressionLimits LIMITS = new HistoryCompressionLimits(0, 2, 32, 1, 128, 1);
   private static final HistoryCompactionPolicy POLICY = HistoryCompactionPolicy.DEFAULTS;

   @Test
   void queueSaturationRetainsRequestAndRetriesWithBackoff() {
      var queued = new ArrayDeque<Runnable>();
      int[] attempts = {0};
      var scheduler = new HistoryCompactionScheduler<HistoryEntry>(task -> {
         if (++attempts[0] == 1) throw new RejectedExecutionException("queue full");
         queued.addLast(task);
      });
      var memory = history();
      var order = order(memory);
      assertTrue(scheduler.poll(memory, order, new BlockHistoryCompactionRules(LIMITS, POLICY), true).isEmpty());
      assertTrue(scheduler.needsTick(memory, new BlockHistoryCompactionRules(LIMITS, POLICY)));
      for (int tick = 0; tick < 20; tick++) scheduler.poll(memory, order, new BlockHistoryCompactionRules(LIMITS, POLICY), true);
      assertEquals(1, attempts[0]);
      scheduler.poll(memory, order, new BlockHistoryCompactionRules(LIMITS, POLICY), true);
      assertEquals(2, attempts[0]);
      queued.removeFirst().run();
      assertEquals(2, scheduler.poll(memory, order, new BlockHistoryCompactionRules(LIMITS, POLICY), true).orElseThrow().removed().size());
   }

   @Test
   void finishingRedoAutomaticallyResumesWithoutExplicitRequest() {
      var queued = new ArrayDeque<Runnable>();
      var scheduler = new HistoryCompactionScheduler<HistoryEntry>(queued::addLast);
      var memory = history();
      var order = order(memory);
      var undone = memory.commit(true);
      order.commit(true, undone.operationId());
      scheduler.poll(memory, order, new BlockHistoryCompactionRules(LIMITS, POLICY), true);
      assertTrue(queued.isEmpty());
      assertFalse(scheduler.needsTick(memory, new BlockHistoryCompactionRules(LIMITS, POLICY)));
      var redone = memory.commit(false);
      order.commit(false, redone.operationId());
      assertTrue(scheduler.needsTick(memory, new BlockHistoryCompactionRules(LIMITS, POLICY)));
      scheduler.poll(memory, order, new BlockHistoryCompactionRules(LIMITS, POLICY), true);
      queued.removeFirst().run();
      assertTrue(scheduler.poll(memory, order, new BlockHistoryCompactionRules(LIMITS, POLICY), true).isPresent());
   }

   @Test
   void staleWorkerCannotReplaceAnIdenticalReloadedCache() {
      var queued = new ArrayDeque<Runnable>();
      var scheduler = new HistoryCompactionScheduler<HistoryEntry>(queued::addLast);
      var original = history();
      var catalog = order(original);
      scheduler.poll(original, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), true);
      var reloaded = new HistoryMemoryCache<HistoryEntry>();
      for (var batch : original.snapshot(true).reversed()) reloaded.addNew(batch);
      assertEquals(original.revision(), reloaded.revision());
      queued.removeFirst().run();
      assertTrue(scheduler.poll(reloaded, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), true).isEmpty());
      scheduler.poll(reloaded, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), true);
      queued.removeFirst().run();
      assertTrue(scheduler.poll(reloaded, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), true).isPresent());
   }

   @Test
   void busyHistoryWaitsAndDetachedWorkerResultIsDiscarded() {
      var queued = new ArrayDeque<Runnable>();
      var scheduler = new HistoryCompactionScheduler<HistoryEntry>(queued::addLast);
      var memory = history();
      var catalog = order(memory);
      scheduler.poll(memory, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), false);
      assertTrue(queued.isEmpty());
      assertTrue(scheduler.needsTick(memory, new BlockHistoryCompactionRules(LIMITS, POLICY)));
      scheduler.poll(memory, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), true);
      assertTrue(scheduler.running());
      scheduler.clear();
      assertFalse(scheduler.running());
      queued.removeFirst().run();
      assertTrue(scheduler.poll(memory, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), true).isEmpty());
      assertEquals(7, memory.size(true));
      assertTrue(scheduler.running());
   }

   @Test
   void applyingAGroupAutomaticallySchedulesFurtherCompression() {
      var scheduler = new HistoryCompactionScheduler<HistoryEntry>(Runnable::run);
      var memory = history();
      var catalog = order(memory);
      scheduler.poll(memory, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), true);
      var change = scheduler.poll(memory, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), true).orElseThrow();
      memory.replaceUndo(change);
      catalog.replaceUndo(change);
      assertTrue(scheduler.needsTick(memory, new BlockHistoryCompactionRules(LIMITS, POLICY)));
      scheduler.poll(memory, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), true);
      assertTrue(scheduler.poll(memory, catalog, new BlockHistoryCompactionRules(LIMITS, POLICY), true).isEmpty());
      assertFalse(scheduler.needsTick(memory, new BlockHistoryCompactionRules(LIMITS, POLICY)));
   }

   private static HistoryMemoryCache<HistoryEntry> history() {
      var memory = new HistoryMemoryCache<HistoryEntry>();
      memory.addNew(edit(BlockPos.ZERO, "0", "1", true));
      memory.addNew(edit(BlockPos.ZERO, "1", "2", true));
      for (int index = 0; index < 5; index++) memory.addNew(edit(new BlockPos(0, 10, index), "0", "1", false));
      return memory;
   }

   private static HistoryOrderCatalog order(HistoryMemoryCache<HistoryEntry> memory) {
      return HistoryOrderCatalog.merge(memory.operationIds(true), memory.operationIds(false), List.of(), List.of());
   }

   private static WorldChangeBatch edit(BlockPos pos, String before, String after, boolean compactable) {
      var batch = WorldChangeBatch.capturePairsByPos(Level.OVERWORLD, List.of(snapshot(pos, before)),
         Map.of(pos, snapshot(pos, after))).orElseThrow().withOperationId(UUID.randomUUID());
      return compactable ? batch.withTinker(BlockEditHistoryMetadata.fromVanillaEdit()) : batch;
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String value) {
      var tag = new CompoundTag();
      tag.putString("marker", value);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }
}
