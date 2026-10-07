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
   void protectsFiveArbitraryRecordsAndRetainsChronologicalPosition() {
      var recent = new ArrayList<WorldChangeBatch>();
      recent.add(edit(BlockPos.ZERO, "0", "1", "upper", 1));
      for (int index = 1; index < 5; index++) recent.add(edit(new BlockPos(index, 0, 0), "0", "1", null, 1));
      var newest = edit(BlockPos.ZERO, "1", "2", "upper", 1);
      var oldest = edit(BlockPos.ZERO, "0", "1", "upper", 1);
      var tail = edit(BlockPos.ZERO, "-1", "0", null, 1);
      var history = new ArrayList<>(recent);
      history.addAll(List.of(newest, oldest, tail));
      var replacement = TinkerHistoryCompactor.compact(history, 32).orElseThrow();
      assertEquals(5, replacement.start());
      assertEquals(List.of(newest.operationId(), oldest.operationId()), replacement.removed());
      var cache = cache(history);
      var order = catalog(cache);
      cache.replaceUndo(replacement);
      order.replaceUndo(replacement);
      assertEquals(recent, cache.snapshot(true).subList(0, 5));
      assertEquals(tail, cache.snapshot(true).getLast());
      assertEquals(cache.operationIds(true), order.order(true));
      assertEquals(cache.snapshot(true).stream().mapToLong(WorldChangeBatch::estimatedBytes).sum(), cache.estimatedBytes());
   }

   @Test
   void repeatedEditsKeepOldestBeforeAndNewestAfterIncludingEntityData() {
      var plan = TinkerHistoryCompactor.compact(withRecent(List.of(
         edit(BlockPos.ZERO, "1", "2", "same", 1), edit(BlockPos.ZERO, "0", "1", "same", 1))), 32).orElseThrow();
      var merged = plan.batch().orElseThrow();
      assertTrue(merged.targetSnapshots(true).getFirst().sameContents(snapshot(BlockPos.ZERO, "0")));
      assertTrue(merged.sourceSnapshots(true).getFirst().sameContents(snapshot(BlockPos.ZERO, "2")));
      assertEquals(2, merged.tinker().actions());
      var cache = cache(withRecent(List.of(merged)));
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
   void netNoOpRemovesOnlyTheOldRun() {
      var plan = TinkerHistoryCompactor.compact(withRecent(List.of(
         edit(BlockPos.ZERO, "1", "0", "same", 1), edit(BlockPos.ZERO, "0", "1", "same", 1))), 32).orElseThrow();
      assertTrue(plan.batch().isEmpty());
      var cache = cache(withRecent(List.of(
         edit(BlockPos.ZERO, "1", "0", "same", 1), edit(BlockPos.ZERO, "0", "1", "same", 1))));
      // Use the exact source identities when publishing a plan.
      var actual = TinkerHistoryCompactor.compact(cache.snapshot(true), 32).orElseThrow();
      var order = catalog(cache);
      cache.replaceUndo(actual);
      order.replaceUndo(actual);
      assertEquals(5, cache.size(true));
      assertEquals(cache.operationIds(true), order.order(true));
   }

   @Test
   void rejectsExternalChangesNonWrenchBoundariesDifferentRegionsAndSeparatedBlocks() {
      for (var older : List.of(
         edit(BlockPos.ZERO, "0", "external", "upper", 1),
         edit(BlockPos.ZERO, "0", "1", null, 1),
         edit(BlockPos.ZERO, "0", "1", "lower", 1),
         edit(new BlockPos(2, 0, 0), "0", "1", "upper", 1),
         edit(new BlockPos(1, 1, 0), "0", "1", "upper", 1),
         edit(BlockPos.ZERO, "0", "1", "upper", 1).withTinker(new BlockEditHistoryMetadata("type", 1)),
         edit(BlockPos.ZERO, "0", "1", "upper", 1, Level.NETHER))) {
         assertTrue(TinkerHistoryCompactor.compact(withRecent(List.of(
            edit(BlockPos.ZERO, "1", "2", "upper", 1), older)), 32).isEmpty());
      }
   }

   @Test
   void joinsConnectedSpatialEditsWithoutReorderingDifferentClusters() {
      var a = edit(new BlockPos(1, 0, 0), "0", "1", "upper", 1);
      var b = edit(BlockPos.ZERO, "0", "1", "upper", 1);
      var c = edit(BlockPos.ZERO, "1", "2", "lower", 1);
      var d = edit(BlockPos.ZERO, "2", "3", "upper", 1);
      var plan = TinkerHistoryCompactor.compact(withRecent(List.of(d, c, a, b)), 32).orElseThrow();
      assertEquals(7, plan.start());
      assertEquals(List.of(a.operationId(), b.operationId()), plan.removed());
      assertEquals(2, plan.batch().orElseThrow().size());
   }

   @Test
   void countsOriginalActionsAndHonorsConfiguredLimit() {
      var history = withRecent(List.of(
         edit(BlockPos.ZERO, "2", "3", "same", 1),
         edit(BlockPos.ZERO, "1", "2", "same", 31),
         edit(BlockPos.ZERO, "0", "1", "same", 1)));
      var plan = TinkerHistoryCompactor.compact(history, 32).orElseThrow();
      assertEquals(2, plan.removed().size());
      assertEquals(32, plan.batch().orElseThrow().tinker().actions());
      assertTrue(TinkerHistoryCompactor.compact(history, 31).isEmpty());
      assertTrue(TinkerHistoryCompactor.compact(history, 1).isEmpty());
      var many = new ArrayList<WorldChangeBatch>();
      for (int i = 40; i > 0; i--) many.add(edit(BlockPos.ZERO, "" + (i - 1), "" + i, "same", 1));
      assertEquals(32, TinkerHistoryCompactor.compact(withRecent(many), 32).orElseThrow().removed().size());
      assertEquals(40, TinkerHistoryCompactor.compact(withRecent(many), 64).orElseThrow().removed().size());
   }

   @Test
   void staleWorkerResultsAreRejectedAfterEveryHistoryMutation() {
      for (String mutation : List.of("write", "undo", "roundtrip", "trim", "load", "cancel", "order", "limit")) {
         var cache = cache(withRecent(List.of(edit(BlockPos.ZERO, "1", "2", "same", 1),
            edit(BlockPos.ZERO, "0", "1", "same", 1))));
         var order = catalog(cache);
         var job = HistoryCompactionJob.start(cache, order, 32).orElseThrow();
         assertTrue(job.matches(cache, order, 32));
         switch (mutation) {
            case "write" -> cache.addNew(edit(BlockPos.ZERO, "2", "3", "same", 1));
            case "undo" -> cache.commit(true);
            case "roundtrip" -> { cache.commit(true); cache.commit(false); }
            case "trim" -> cache.trim(6, Long.MAX_VALUE);
            case "load" -> cache.merge(true, List.of());
            case "cancel" -> cache.removeNewestUndo();
            case "order" -> order.remove(order.order(true).getLast());
            case "limit" -> { }
         }
         assertFalse(job.matches(cache, order, mutation.equals("limit") ? 16 : 32), mutation);
         job.result.join();
      }
   }

   @Test
   void refusesRedoAndGapsInLoadedHistory() {
      var cache = cache(withRecent(List.of(edit(BlockPos.ZERO, "1", "2", "same", 1),
         edit(BlockPos.ZERO, "0", "1", "same", 1))));
      var order = catalog(cache);
      order.addNew(UUID.randomUUID());
      assertTrue(HistoryCompactionJob.start(cache, order, 32).isEmpty());
      order = catalog(cache);
      var undone = cache.commit(true);
      order.commit(true, undone.operationId());
      assertTrue(HistoryCompactionJob.start(cache, order, 32).isEmpty());
   }

   @Test
   void metadataRoundTripsAndRejectsInvalidCounts() throws Exception {
      var metadata = new BlockEditHistoryMetadata("north:upper:shape", 32);
      CompoundTag root = new CompoundTag();
      root.put("Tinker", metadata.encode());
      assertEquals(metadata, BlockEditHistoryMetadata.decode(root));
      root.getCompound("Tinker").putInt("Actions", 0);
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
      root.putString("Tinker", "invalid");
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
   }

   @Test
   void distanceBandsUse8And16And32OriginalActionsAndRemainSeparate() {
      for (var band : TinkerHistoryMergeLimits.DistanceBand.values()) {
         var edits = new ArrayList<WorldChangeBatch>();
         for (int i = 40; i > 0; i--) edits.add(edit(BlockPos.ZERO, "" + (i - 1), "" + i, "same", 1)
            .withTinker(new BlockEditHistoryMetadata("same", 1, band)));
         var plan = TinkerHistoryCompactor.compact(withRecent(edits), TinkerHistoryMergeLimits.DEFAULTS).orElseThrow();
         assertEquals(TinkerHistoryMergeLimits.DEFAULTS.limit(band), plan.batch().orElseThrow().tinker().actions());
         assertEquals(band, plan.batch().orElseThrow().tinker().distanceBand());
      }
      var near = edit(BlockPos.ZERO, "1", "2", "same", 1).withTinker(new BlockEditHistoryMetadata("same", 1, TinkerHistoryMergeLimits.DistanceBand.NEAR));
      var middle = edit(BlockPos.ZERO, "0", "1", "same", 1).withTinker(new BlockEditHistoryMetadata("same", 1, TinkerHistoryMergeLimits.DistanceBand.MIDDLE));
      assertTrue(TinkerHistoryCompactor.compact(withRecent(List.of(near, middle)), TinkerHistoryMergeLimits.DEFAULTS).isEmpty());
   }

   @Test
   void persistedBandSurvivesReloadAndOldMetadataFallsBackToFar() throws Exception {
      var root = new CompoundTag();
      var metadata = new BlockEditHistoryMetadata("same", 8, TinkerHistoryMergeLimits.DistanceBand.NEAR);
      root.put("Tinker", metadata.encode());
      assertEquals(metadata, BlockEditHistoryMetadata.decode(root));
      root.getCompound("Tinker").remove("DistanceBand");
      assertEquals(TinkerHistoryMergeLimits.DistanceBand.FAR, BlockEditHistoryMetadata.decode(root).distanceBand());
      root.getCompound("Tinker").putString("DistanceBand", "invalid");
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
   }

   private static HistoryMemoryCache cache(List<WorldChangeBatch> newestFirst) {
      var cache = new HistoryMemoryCache();
      for (var batch : newestFirst.reversed()) cache.addNew(batch);
      return cache;
   }

   @Test
   void configuredProtectionConnectivityAndBudgetsAreApplied() {
      var defaults = HistoryCompactionPolicy.DEFAULTS;
      var recent = edit(new BlockPos(0, 10, 0), "0", "1", null, 1);
      var older = edit(BlockPos.ZERO, "0", "1", "same", 1);
      var newer = edit(new BlockPos(1, 1, 0), "0", "1", "same", 1);
      var history = List.of(recent, newer, older);
      var diagonal = new HistoryCompactionPolicy(true, 1, defaults.maxCells(), defaults.maxBytes(), true, defaults.grouping());
      var plan = TinkerHistoryCompactor.compact(history, TinkerHistoryMergeLimits.DEFAULTS, diagonal).orElseThrow();
      assertEquals(1, plan.start());
      assertEquals(2, plan.batch().orElseThrow().size());
      assertTrue(TinkerHistoryCompactor.compact(history, TinkerHistoryMergeLimits.DEFAULTS,
         new HistoryCompactionPolicy(true, 1, defaults.maxCells(), defaults.maxBytes(), false, defaults.grouping())).isEmpty());
      assertTrue(TinkerHistoryCompactor.compact(history, TinkerHistoryMergeLimits.DEFAULTS, defaults).isEmpty());
      for (var disabled : List.of(
         new HistoryCompactionPolicy(false, 1, defaults.maxCells(), defaults.maxBytes(), true, defaults.grouping()),
         new HistoryCompactionPolicy(true, 1, 1, defaults.maxBytes(), true, defaults.grouping()),
         new HistoryCompactionPolicy(true, 1, defaults.maxCells(), 1, true, defaults.grouping()))) {
         assertTrue(TinkerHistoryCompactor.compact(history, TinkerHistoryMergeLimits.DEFAULTS, disabled).isEmpty());
      }
      assertThrows(IllegalArgumentException.class, () -> new HistoryCompactionPolicy(true, 0, 1, 1, false, defaults.grouping()));
   }

   @Test
   void configReloadInvalidatesTheWorkerResult() {
      var cache = cache(withRecent(List.of(edit(BlockPos.ZERO, "1", "2", "same", 1),
         edit(BlockPos.ZERO, "0", "1", "same", 1))));
      var order = catalog(cache);
      var defaults = HistoryCompactionPolicy.DEFAULTS;
      var job = HistoryCompactionJob.start(cache, order, TinkerHistoryMergeLimits.DEFAULTS, defaults).orElseThrow();
      var changed = new HistoryCompactionPolicy(true, 5, defaults.maxCells(), defaults.maxBytes(), true, defaults.grouping());
      assertFalse(job.matches(cache, order, TinkerHistoryMergeLimits.DEFAULTS, changed));
      var grouping = new TinkerHistoryGroupingRules(java.util.Set.of(TinkerHistoryGroupingRules.KeyPart.FACE), defaults.grouping().generalParts(), java.util.Set.of());
      changed = new HistoryCompactionPolicy(true, 5, defaults.maxCells(), defaults.maxBytes(), false, grouping);
      assertFalse(job.matches(cache, order, TinkerHistoryMergeLimits.DEFAULTS, changed));
      job.result.join();
   }

   private static HistoryOrderCatalog catalog(HistoryMemoryCache cache) {
      return HistoryOrderCatalog.merge(cache.operationIds(true), cache.operationIds(false), List.of(), List.of());
   }

   private static List<WorldChangeBatch> withRecent(List<WorldChangeBatch> older) {
      var result = new ArrayList<WorldChangeBatch>();
      for (int i = 0; i < 5; i++) result.add(edit(new BlockPos(i, 10, 0), "0", "1", null, 1));
      result.addAll(older);
      return result;
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
