package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class HistoryMemoryCacheTest {
   private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
      ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("minecraft", "dimension")),
      ResourceLocation.withDefaultNamespace("overworld")
   );

   @Test
   void demandPagingMakesProgressWithOnlyTwoBatchesOfMemoryInEitherDirection() {
      for (boolean undo : new boolean[] {true, false}) {
         HistoryMemoryCache cache = new HistoryMemoryCache();
         List<WorldChangeBatch> durable = java.util.stream.IntStream.range(0, 5)
            .mapToObj(index -> batch(new BlockPos(index, 0, 0), UUID.randomUUID())).toList();
         long byteLimit = durable.stream().mapToLong(WorldChangeBatch::estimatedBytes).max().orElseThrow() * 2;
         List<UUID> expected = durable.stream().map(WorldChangeBatch::operationId).toList();
         HistoryOrderCatalog order = HistoryOrderCatalog.merge(List.of(), List.of(),
            undo ? expected : List.of(), undo ? List.of() : expected);
         java.util.ArrayList<UUID> applied = new java.util.ArrayList<>();
         int loads = 0;
         while (applied.size() < durable.size()) {
            if (cache.size(undo) == 0) {
               var plan = HistoryPageLoadPlan.create(undo, durable.size() - applied.size(),
                  cache.operationIds(undo), 200, order).orElseThrow();
               cache.merge(undo, durable.stream().filter(value -> plan.operationIds().contains(value.operationId())).toList());
               cache.trim(200, byteLimit);
               loads++;
               assertTrue(loads <= durable.size(), "page loads must make progress");
            }
            WorldChangeBatch committed = cache.commit(undo);
            applied.add(committed.operationId());
            order.commit(undo, committed.operationId());
            cache.trim(200, byteLimit);
            assertTrue(cache.estimatedBytes() <= byteLimit);
         }
         assertEquals(expected, applied);
         assertTrue(loads > 1);
         assertTrue(order.order(undo).isEmpty());
         assertEquals(expected.reversed(), order.order(!undo));
      }
   }

   @Test
   void invalidPageLeavesBothCacheOrderAndByteCountUnchanged() {
      HistoryMemoryCache cache = new HistoryMemoryCache();
      WorldChangeBatch newest = batch(BlockPos.ZERO, UUID.randomUUID());
      WorldChangeBatch older = batch(BlockPos.ZERO.above(), UUID.randomUUID());
      cache.addNew(newest);
      long bytes = cache.estimatedBytes();
      assertThrows(NullPointerException.class, () -> cache.merge(true, java.util.Arrays.asList(older, null)));
      assertEquals(List.of(newest.operationId()), cache.operationIds(true));
      assertEquals(bytes, cache.estimatedBytes());
      cache.merge(true, List.of(older));
      assertEquals(List.of(newest.operationId(), older.operationId()), cache.operationIds(true));
      assertEquals(bytes + older.estimatedBytes(), cache.estimatedBytes());
   }

   @Test
   void commitMovesTheOwnedBatchAndANewBatchClearsRedo() {
      HistoryMemoryCache cache = new HistoryMemoryCache();
      WorldChangeBatch first = batch(BlockPos.ZERO, UUID.randomUUID());
      WorldChangeBatch second = batch(BlockPos.ZERO.above(), UUID.randomUUID());

      cache.addNew(first);
      assertEquals(first, cache.commit(true));
      assertEquals(List.of(first.operationId()), cache.operationIds(false));

      cache.addNew(second);

      assertEquals(List.of(second.operationId()), cache.operationIds(true));
      assertTrue(cache.operationIds(false).isEmpty());
   }

   @Test
   void mergeDeduplicatesAndTrimRetainsTheNewestBatch() {
      HistoryMemoryCache cache = new HistoryMemoryCache();
      WorldChangeBatch newest = batch(BlockPos.ZERO, UUID.randomUUID());
      WorldChangeBatch older = batch(BlockPos.ZERO.above(), UUID.randomUUID());
      cache.addNew(newest);

      cache.merge(true, List.of(newest, older));
      cache.trim(1, Long.MAX_VALUE);

      assertEquals(List.of(newest.operationId()), cache.operationIds(true));
      assertTrue(cache.estimatedBytes() > 0L);
   }

   private static WorldChangeBatch batch(BlockPos pos, UUID operationId) {
      CompoundTag beforeTag = new CompoundTag();
      beforeTag.putString("marker", "before");
      CompoundTag afterTag = new CompoundTag();
      afterTag.putString("marker", "after");
      ReversibleBlockSnapshot before = new ReversibleBlockSnapshot(
         pos, null, null, new BlockEntitySnapshot(beforeTag)
      );
      ReversibleBlockSnapshot after = new ReversibleBlockSnapshot(
         pos, null, null, new BlockEntitySnapshot(afterTag)
      );
      return WorldChangeBatch.fromPairsForTest(DIMENSION, List.of(before), Map.of(pos, after))
         .orElseThrow()
         .withOperationId(operationId);
   }
}
