package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.*;

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

class TinkerHistoryCompactorTest {
   @Test
   void vanillaEditsIgnoreMaterialFaceAndActionKindIncludingOldRecords() {
      var older = edit(BlockPos.ZERO, "0", "1", "vanilla:PLACE:face=up:block=stone", 1);
      var newer = edit(new BlockPos(5, -5, 5), "0", "1", "vanilla:BREAK:face=north:block=dirt", 1);
      var result = TinkerHistoryCompactor.compact(withNewerActions(5, List.of(newer, older)), 2).orElseThrow();
      assertEquals(2, result.batch().orElseThrow().size());
      assertEquals("vanilla", result.batch().orElseThrow().tinker().cluster());
      var fresh = edit(new BlockPos(5, -5, 5), "1", "2", "vanilla", 1);
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(5, List.of(fresh, newer)), 2).isPresent());
   }

   @Test
   void vanillaDistanceChecksConsecutiveBoundsAndStopsBeyondFiveBlocks() {
      var origin = edit(BlockPos.ZERO, "0", "1", "vanilla", 1);
      var far = edit(new BlockPos(6, 0, 0), "0", "1", "vanilla", 1);
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(5, List.of(origin, far)), 2).isEmpty());
      var near = edit(new BlockPos(-5, 0, 0), "0", "1", "vanilla", 1);
      var opposite = edit(new BlockPos(5, 0, 0), "0", "1", "vanilla", 1);
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(5, List.of(origin, near, opposite)), 3).isEmpty());
      var wrench = origin.withTinker(new BlockEditHistoryMetadata("wrench", 1));
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(5, List.of(origin, wrench)), 2).isEmpty());
   }

   @Test
   void mergedBoundsRetainCancelledCellsAndAreReusedForLaterGroups() {
      var left = BlockPos.ZERO;
      var right = new BlockPos(5, 0, 0);
      var run = List.of(edit(left, "1", "0", "vanilla", 1), edit(right, "0", "1", "vanilla", 1),
         edit(left, "0", "1", "vanilla", 1));
      var merged = TinkerHistoryCompactor.compact(withNewerActions(5, run), 3).orElseThrow().batch().orElseThrow();
      assertEquals(1, merged.size());
      assertEquals(right, merged.position(0));
      assertEquals(new HistoryBounds(0, 0, 0, 5, 0, 0), merged.bounds());
      assertSame(merged.bounds(), merged.withOperationId(UUID.randomUUID()).bounds());
      assertSame(merged.bounds(), merged.withTinker(merged.tinker()).bounds());
      assertSame(merged.bounds(), merged.withTinker(merged.tinker(), null).bounds());
      assertEquals(merged.bounds(), merged.withTinker(merged.tinker(), new HistoryBounds(1, 0, 0, 2, 0, 0)).bounds());
      var next = edit(new BlockPos(-5, 0, 0), "0", "1", "vanilla", 3);
      var limits = new TinkerHistoryMergeLimits(0, 3, 1, 2, 2, 2);
      var result = TinkerHistoryCompactor.compact(withNewerActions(5, List.of(next, merged)), limits).orElseThrow();
      assertEquals(new HistoryBounds(-5, 0, 0, 5, 0, 0), result.batch().orElseThrow().bounds());
   }

   @Test
   void protectsRecentRecordsAndRetainsChronologicalPosition() {
      var newest = edit(BlockPos.ZERO, "1", "2", "upper", 1);
      var oldest = edit(BlockPos.ZERO, "0", "1", "upper", 1);
      var tail = edit(BlockPos.ZERO, "-1", "0", null, 1);
      var history = withNewerActions(5, List.of(newest, oldest, tail));
      var replacement = TinkerHistoryCompactor.compact(history, 2).orElseThrow();
      assertEquals(5, replacement.start());
      assertEquals(List.of(newest.operationId(), oldest.operationId()), replacement.removed());
      var cache = cache(history);
      var order = catalog(cache);
      cache.replaceUndo(replacement);
      order.replaceUndo(replacement);
      assertEquals(history.subList(0, 5), cache.snapshot(true).subList(0, 5));
      assertEquals(tail, cache.snapshot(true).getLast());
      assertEquals(cache.operationIds(true), order.order(true));
      assertEquals(cache.snapshot(true).stream().mapToLong(WorldChangeBatch::estimatedBytes).sum(), cache.estimatedBytes());
   }

   @Test
   void composesBeforeAndAfterIncludingEntityDataAndReplaysAsOneRecord() {
      var plan = TinkerHistoryCompactor.compact(withNewerActions(5, run(2, 1, 0)), 2).orElseThrow();
      var merged = plan.batch().orElseThrow();
      assertTrue(merged.targetSnapshots(true).getFirst().sameContents(snapshot(BlockPos.ZERO, "0")));
      assertTrue(merged.sourceSnapshots(true).getFirst().sameContents(snapshot(BlockPos.ZERO, "2")));
      assertEquals(2, merged.tinker().actions());
      var cache = cache(withNewerActions(5, List.of(merged)));
      var order = catalog(cache);
      for (int index = 0; index < 6; index++) {
         var undone = cache.commit(true);
         order.commit(true, undone.operationId());
      }
      assertEquals(merged, cache.peek(false));
      assertEquals(merged, cache.commit(false));
      order.commit(false, merged.operationId());
      assertEquals(cache.operationIds(true), order.order(true));
   }

   @Test
   void netNoOpRemovesOnlyItsRun() {
      var history = withNewerActions(5, List.of(edit(BlockPos.ZERO, "1", "0", "same", 1), edit(BlockPos.ZERO, "0", "1", "same", 1)));
      var plan = TinkerHistoryCompactor.compact(history, 2).orElseThrow();
      assertTrue(plan.batch().isEmpty());
      var cache = cache(history);
      var order = catalog(cache);
      cache.replaceUndo(plan);
      order.replaceUndo(plan);
      assertEquals(5, cache.size(true));
      assertEquals(cache.operationIds(true), order.order(true));
   }

   @Test
   void refusesExternalChangesBoundariesAndDisconnectedRegions() {
      for (var older : List.of(
         edit(BlockPos.ZERO, "0", "external", "upper", 1),
         edit(BlockPos.ZERO, "0", "1", null, 1),
         edit(BlockPos.ZERO, "0", "1", "lower", 1),
         edit(new BlockPos(2, 0, 0), "0", "1", "upper", 1),
         edit(new BlockPos(1, 1, 0), "0", "1", "upper", 1),
         edit(BlockPos.ZERO, "0", "1", "upper", 1, Level.NETHER))) {
         assertTrue(TinkerHistoryCompactor.compact(withNewerActions(5, List.of(
            edit(BlockPos.ZERO, "1", "2", "upper", 1), older)), 2).isEmpty());
      }
      var a = edit(new BlockPos(1, 0, 0), "0", "1", "upper", 1);
      var b = edit(BlockPos.ZERO, "0", "1", "upper", 1);
      var c = edit(BlockPos.ZERO, "1", "2", "lower", 1);
      var d = edit(BlockPos.ZERO, "2", "3", "upper", 1);
      var plan = TinkerHistoryCompactor.compact(withNewerActions(5, List.of(d, c, a, b)), 2).orElseThrow();
      assertEquals(7, plan.start());
      assertEquals(List.of(a.operationId(), b.operationId()), plan.removed());
      assertEquals(2, plan.batch().orElseThrow().size());
   }

   @Test
   void thresholdMeansStrictlyMoreNewActionsAndRequiresAWholeGroup() {
      var limits = TinkerHistoryMergeLimits.DEFAULTS;
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(5, run(8, 1, 0)), limits).isEmpty());
      assertEquals(8, TinkerHistoryCompactor.compact(withNewerActions(6, run(8, 1, 0)), limits).orElseThrow().removed().size());
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(6, run(7, 1, 0)), limits).isEmpty());
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(32, run(4, 8, 1)), limits).isEmpty());
      var older = TinkerHistoryCompactor.compact(withNewerActions(33, run(4, 8, 1)), limits).orElseThrow();
      assertEquals(4, older.removed().size());
      assertEquals(new BlockEditHistoryMetadata("same", 32, 2), older.batch().orElseThrow().tinker());
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(33, run(3, 8, 1)), limits).isEmpty());
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(128, run(4, 32, 2)), limits).isEmpty());
      assertEquals(new BlockEditHistoryMetadata("same", 128, 3), TinkerHistoryCompactor.compact(
         withNewerActions(129, run(4, 32, 2)), limits).orElseThrow().batch().orElseThrow().tinker());
   }

   @Test
   void eachTierRunsOnceEvenWhenRecordsAreVeryOld() {
      var limits = TinkerHistoryMergeLimits.DEFAULTS;
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(10, run(8, 8, 1)),
         new TinkerHistoryMergeLimits(5, 8, 1000, 4, 2000, 4)).isEmpty());
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(64, run(4, 32, 2)), limits).isEmpty());
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(1000, run(4, 128, 3)), limits).isEmpty());
      // A later pass must not keep merging its own output, even after another reload.
      var history = withNewerActions(6, run(16, 1, 0));
      var cache = cache(history);
      for (int pass = 0; pass < 2; pass++) cache.replaceUndo(TinkerHistoryCompactor.compact(cache.snapshot(true), limits).orElseThrow());
      assertEquals(8, cache.size(true));
      assertTrue(TinkerHistoryCompactor.compact(cache.snapshot(true), limits).isEmpty());
   }

   @Test
   void laterTiersCountRecordsRatherThanOriginalActions() {
      var mixed = new ArrayList<>(run(4, 1, 1));
      mixed.set(0, mixed.getFirst().withTinker(new BlockEditHistoryMetadata("same", 8, 1)));
      mixed.set(2, mixed.get(2).withTinker(new BlockEditHistoryMetadata("same", 32, 1)));
      var result = TinkerHistoryCompactor.compact(withNewerActions(33, mixed), TinkerHistoryMergeLimits.DEFAULTS).orElseThrow();
      assertEquals(4, result.removed().size());
      assertEquals(new BlockEditHistoryMetadata("same", 42, 2), result.batch().orElseThrow().tinker());
   }

   @Test
   void laterTiersOnlyConsumeThePreviousEnabledTier() {
      var limits = TinkerHistoryMergeLimits.DEFAULTS;
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(200, run(4, 1, 0)), limits).isEmpty());
      var recentGroups = withNewerActions(200, run(4, 8, 1));
      var older = TinkerHistoryCompactor.compact(recentGroups, limits).orElseThrow().batch().orElseThrow();
      assertEquals(2, older.tinker().compressionStage());
      var onlyOldest = new TinkerHistoryMergeLimits(5, 1, 32, 1, 128, 4);
      assertEquals(3, TinkerHistoryCompactor.compact(withNewerActions(129, run(4, 1, 0)), onlyOldest)
         .orElseThrow().batch().orElseThrow().tinker().compressionStage());
   }

   @Test
   void ageCountsOriginalActionsInNewerCompressedRecords() {
      var history = new ArrayList<>(withNewerActions(5, List.of()));
      history.set(0, history.getFirst().withTinker(new BlockEditHistoryMetadata("newer", 29, 1)));
      history.addAll(run(4, 8, 1));
      var result = TinkerHistoryCompactor.compact(history, TinkerHistoryMergeLimits.DEFAULTS).orElseThrow();
      assertEquals(5, result.start());
      assertEquals(32, result.batch().orElseThrow().tinker().actions());
   }

   @Test
   void countsBeyondOldMetadataLimitSurviveAndOverflowIsRejected() throws Exception {
      var result = TinkerHistoryCompactor.compact(withNewerActions(129, run(4, 20000, 2)), TinkerHistoryMergeLimits.DEFAULTS).orElseThrow();
      var metadata = result.batch().orElseThrow().tinker();
      assertEquals(80000, metadata.actions());
      var root = new CompoundTag();
      root.put("Tinker", metadata.encode());
      assertEquals(metadata, BlockEditHistoryMetadata.decode(root));
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(129, run(4, Integer.MAX_VALUE, 2)), TinkerHistoryMergeLimits.DEFAULTS).isEmpty());
   }

   @Test
   void metadataPreservesStageAndMigratesLegacyGroupsWithoutDistance() throws Exception {
      for (int stage = 0; stage <= 3; stage++) {
         var root = new CompoundTag();
         var metadata = new BlockEditHistoryMetadata("same", 8, stage);
         root.put("Tinker", metadata.encode());
         assertEquals(metadata, BlockEditHistoryMetadata.decode(root));
      }
      var root = new CompoundTag();
      root.put("Tinker", new BlockEditHistoryMetadata("same", 8, 2).encode());
      root.getCompound("Tinker").remove("CompressionStage");
      root.getCompound("Tinker").putString("DistanceBand", "NEAR");
      assertEquals(new BlockEditHistoryMetadata("same", 8, 1), BlockEditHistoryMetadata.decode(root));
      root.getCompound("Tinker").putInt("Actions", 1);
      assertEquals(0, BlockEditHistoryMetadata.decode(root).compressionStage());
      root.getCompound("Tinker").putInt("Actions", 0);
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
      root.getCompound("Tinker").putInt("Actions", 1);
      root.getCompound("Tinker").putInt("CompressionStage", 4);
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
      root.getCompound("Tinker").putString("CompressionStage", "bad");
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
      root.putString("Tinker", "invalid");
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
   }

   @Test
   void appliesConfiguredProtectionConnectivityAndBudgetsAtEveryTier() {
      var defaults = HistoryCompactionPolicy.DEFAULTS;
      var recent = edit(new BlockPos(0, 10, 0), "0", "1", null, 1);
      var older = edit(BlockPos.ZERO, "0", "1", "same", 1);
      var newer = edit(new BlockPos(1, 1, 0), "0", "1", "same", 1);
      var history = List.of(recent, newer, older);
      var limits = TinkerHistoryMergeLimits.uniform(2);
      var diagonal = new HistoryCompactionPolicy(true, 1, defaults.maxCells(), defaults.maxBytes(), true, defaults.grouping());
      assertEquals(1, TinkerHistoryCompactor.compact(history, limits, diagonal).orElseThrow().start());
      assertTrue(TinkerHistoryCompactor.compact(history, limits,
         new HistoryCompactionPolicy(true, 1, defaults.maxCells(), defaults.maxBytes(), false, defaults.grouping())).isEmpty());
      assertTrue(TinkerHistoryCompactor.compact(history, limits, defaults).isEmpty());
      for (var policy : List.of(
         new HistoryCompactionPolicy(false, 1, defaults.maxCells(), defaults.maxBytes(), true, defaults.grouping()),
         new HistoryCompactionPolicy(true, 1, 1, defaults.maxBytes(), true, defaults.grouping()),
         new HistoryCompactionPolicy(true, 1, defaults.maxCells(), 1, true, defaults.grouping()))) {
         assertTrue(TinkerHistoryCompactor.compact(history, limits, policy).isEmpty());
      }
      assertTrue(TinkerHistoryCompactor.compact(withNewerActions(200, run(4, 8, 1)), new TinkerHistoryMergeLimits(5, 1, 32, 1, 128, 1)).isEmpty());
      assertThrows(IllegalArgumentException.class, () -> new HistoryCompactionPolicy(true, 0, 1, 1, false, defaults.grouping()));
   }

   @Test
   void workerResultsAreRejectedAfterHistoryOrConfigChanges() {
      for (String mutation : List.of("write", "undo", "roundtrip", "trim", "load", "cancel", "order", "limit", "policy")) {
         var cache = cache(withNewerActions(5, run(2, 1, 0)));
         var order = catalog(cache);
         var limits = TinkerHistoryMergeLimits.uniform(2);
         var policy = HistoryCompactionPolicy.DEFAULTS;
         var job = HistoryCompactionJob.start(cache, order, limits, policy).orElseThrow();
         assertTrue(job.matches(cache, order, limits, policy));
         switch (mutation) {
            case "write" -> cache.addNew(edit(BlockPos.ZERO, "2", "3", "same", 1));
            case "undo" -> cache.commit(true);
            case "roundtrip" -> { cache.commit(true); cache.commit(false); }
            case "trim" -> cache.trim(6, Long.MAX_VALUE);
            case "load" -> cache.merge(true, List.of());
            case "cancel" -> cache.removeNewestUndo();
            case "order" -> order.remove(order.order(true).getLast());
            case "limit" -> limits = limits.with(TinkerHistoryMergeLimits.Setting.OLDER_GROUP, 3);
            case "policy" -> policy = new HistoryCompactionPolicy(true, 5, policy.maxCells(), policy.maxBytes(), true, policy.grouping());
         }
         assertFalse(job.matches(cache, order, limits, policy), mutation);
         job.result.join();
      }
   }

   @Test
   void workerRefusesRedoAndGapsInLoadedHistory() {
      var cache = cache(withNewerActions(5, run(2, 1, 0)));
      var order = catalog(cache);
      order.addNew(UUID.randomUUID());
      assertTrue(HistoryCompactionJob.start(cache, order, 2).isEmpty());
      order = catalog(cache);
      var undone = cache.commit(true);
      order.commit(true, undone.operationId());
      assertTrue(HistoryCompactionJob.start(cache, order, 2).isEmpty());
   }

   private static List<WorldChangeBatch> run(int records, int actionsPerRecord, int stage) {
      var result = new ArrayList<WorldChangeBatch>();
      for (int index = records; index > 0; index--) result.add(edit(BlockPos.ZERO, "" + (index - 1), "" + index, "same", actionsPerRecord)
         .withTinker(new BlockEditHistoryMetadata("same", actionsPerRecord, stage)));
      return result;
   }

   private static List<WorldChangeBatch> withNewerActions(int actions, List<WorldChangeBatch> tail) {
      var history = new ArrayList<WorldChangeBatch>();
      for (int index = 0; index < actions; index++) history.add(edit(new BlockPos(0, 10, index), "0", "1", null, 1));
      history.addAll(tail);
      return history;
   }

   private static HistoryMemoryCache cache(List<WorldChangeBatch> newestFirst) {
      var cache = new HistoryMemoryCache();
      for (var batch : newestFirst.reversed()) cache.addNew(batch);
      return cache;
   }

   private static HistoryOrderCatalog catalog(HistoryMemoryCache cache) {
      return HistoryOrderCatalog.merge(cache.operationIds(true), cache.operationIds(false), List.of(), List.of());
   }

   private static WorldChangeBatch edit(BlockPos position, String before, String after, String cluster, int count) {
      return edit(position, before, after, cluster, count, Level.OVERWORLD);
   }

   private static WorldChangeBatch edit(BlockPos position, String before, String after, String cluster, int count,
      net.minecraft.resources.ResourceKey<Level> dimension) {
      var batch = WorldChangeBatch.fromPairsForTest(dimension, List.of(snapshot(position, before)),
         Map.of(position, snapshot(position, after))).orElseThrow().withOperationId(UUID.randomUUID());
      return cluster == null ? batch : batch.withTinker(new BlockEditHistoryMetadata(cluster, count));
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position, String value) {
      var tag = new CompoundTag();
      tag.putString("marker", value);
      return new ReversibleBlockSnapshot(position, null, null, new BlockEntitySnapshot(tag));
   }
}
