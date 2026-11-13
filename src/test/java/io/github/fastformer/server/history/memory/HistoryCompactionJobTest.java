package io.github.fastformer.server.history.memory;

import io.github.fastformer.common.history.CompressionSetting;

import io.github.fastformer.server.history.compaction.BlockHistoryCompactionRules;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.server.history.compaction.HistoryCompactionPolicy;
import io.github.fastformer.common.history.HistoryCompressionLimits;
import io.github.fastformer.server.history.entry.BlockEditHistoryMetadata;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.fastplace.world.snapshot.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class HistoryCompactionJobTest {
   @Test
   void workerResultsAreRejectedAfterHistoryOrConfigChanges() {
      for (String mutation : List.of("write", "undo", "roundtrip", "trim", "load", "cancel", "order", "limit", "policy")) {
         var cache = cache(withNewerActions(5, run(2, 1, 0)));
         var order = catalog(cache);
         var limits = new HistoryCompressionLimits(0, 2, 32, 1, 128, 1);
         var policy = HistoryCompactionPolicy.DEFAULTS;
         var job = HistoryCompactionJob.start(cache, order, new BlockHistoryCompactionRules(limits, policy), Runnable::run).orElseThrow();
         assertTrue(job.matches(cache, order, new BlockHistoryCompactionRules(limits, policy)));
         switch (mutation) {
            case "write" -> cache.addNew(edit(BlockPos.ZERO, "2", "3", "same", 1));
            case "undo" -> cache.commit(true);
            case "roundtrip" -> { cache.commit(true); cache.commit(false); }
            case "trim" -> cache.trim(6, Long.MAX_VALUE);
            case "load" -> cache.merge(true, List.of());
            case "cancel" -> cache.removeNewestUndo();
            case "order" -> order.remove(order.order(true).getLast());
            case "limit" -> limits = limits.with(CompressionSetting.OLDER_GROUP, 3);
            case "policy" -> policy = new HistoryCompactionPolicy(true, 5, policy.maxCells(), policy.maxBytes(), true, policy.grouping());
         }
         assertFalse(job.matches(cache, order, new BlockHistoryCompactionRules(limits, policy)), mutation);
         job.result.join();
      }
   }

   @Test
   void workerRefusesRedoAndGapsInLoadedHistory() {
      var cache = cache(withNewerActions(5, run(2, 1, 0)));
      var order = catalog(cache);
      order.addNew(UUID.randomUUID());
      assertTrue(HistoryCompactionJob.start(cache, order, new BlockHistoryCompactionRules(new HistoryCompressionLimits(0, 2, 32, 1, 128, 1), HistoryCompactionPolicy.DEFAULTS), Runnable::run).isEmpty());
      order = catalog(cache);
      var undone = cache.commit(true);
      order.commit(true, undone.operationId());
      assertTrue(HistoryCompactionJob.start(cache, order, new BlockHistoryCompactionRules(new HistoryCompressionLimits(0, 2, 32, 1, 128, 1), HistoryCompactionPolicy.DEFAULTS), Runnable::run).isEmpty());
   }

   private static List<HistoryEntry> run(int records, int actionsPerRecord, int stage) {
      var result = new ArrayList<HistoryEntry>();
      for (int index = records; index > 0; index--) result.add(edit(BlockPos.ZERO, "" + (index - 1), "" + index, "same", actionsPerRecord)
         .withTinker(new BlockEditHistoryMetadata("same", actionsPerRecord, stage)));
      return result;
   }

   private static List<HistoryEntry> withNewerActions(int actions, List<HistoryEntry> tail) {
      var history = new ArrayList<HistoryEntry>();
      for (int index = 0; index < actions; index++) history.add(edit(new BlockPos(0, 10, index), "0", "1", null, 1));
      history.addAll(tail);
      return history;
   }

   private static HistoryMemoryCache<HistoryEntry> cache(List<HistoryEntry> newestFirst) {
      var cache = new HistoryMemoryCache<HistoryEntry>();
      for (var batch : newestFirst.reversed()) cache.addNew(batch);
      return cache;
   }

   private static HistoryOrderCatalog catalog(HistoryMemoryCache<HistoryEntry> cache) {
      return HistoryOrderCatalog.merge(cache.operationIds(true), cache.operationIds(false), List.of(), List.of());
   }

   private static WorldChangeBatch edit(BlockPos position, String before, String after, String cluster, int count) {
      return edit(position, before, after, cluster, count, Level.OVERWORLD);
   }

   private static WorldChangeBatch edit(BlockPos position, String before, String after, String cluster, int count,
      net.minecraft.resources.ResourceKey<Level> dimension) {
      var batch = WorldChangeBatch.capturePairsByPos(dimension, List.of(snapshot(position, before)),
         Map.of(position, snapshot(position, after))).orElseThrow().withOperationId(UUID.randomUUID());
      return cluster == null ? batch : batch.withTinker(new BlockEditHistoryMetadata(cluster, count));
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position, String value) {
      var tag = new CompoundTag();
      tag.putString("marker", value);
      return new ReversibleBlockSnapshot(position, null, null, new BlockEntitySnapshot(tag));
   }
}
