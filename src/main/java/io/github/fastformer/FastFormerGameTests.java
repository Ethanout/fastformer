package io.github.fastformer;

import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.geometry.generation.BlockPositionSource;
import io.github.fastformer.fastplace.geometry.generation.LineGenerator;
import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

/** Runtime checks kept separate from player workflow tests. */
@GameTestHolder(FastFormer.MOD_ID)
public final class FastFormerGameTests {
   private FastFormerGameTests() {
   }

   public static void register(RegisterGameTestsEvent event) {
      event.register(FastFormerGameTests.class);
      event.register(io.github.fastformer.fastplace.world.JournalRecoveryGameTests.class);
   }

   @GameTest(template = "empty", timeoutTicks = 20)
   public static void modLoads(GameTestHelper helper) {
      helper.succeed();
   }

   @GameTest(template = "empty", timeoutTicks = 40)
   public static void lazyLineAndTransactionBoundariesWorkInRuntime(GameTestHelper helper) {
      var generated = LineGenerator.generate(BlockPos.ZERO, new BlockPos(100_000, 3, 2), 100_001);
      if (generated.size() != 100_001) {
         helper.fail("unexpected lazy line size: " + generated.size());
         return;
      }
      BlockGenerationResult result = BlockGenerationResult.fromLegacy(generated);
      BlockPositionSource source = result.positionSource();
      if (!source.supportsDraining() || !source.iterator().next().equals(BlockPos.ZERO)) {
         helper.fail("generated position source lost its ownership boundary");
         return;
      }

      WorldChangeTransaction transaction = new WorldChangeTransaction();
      BlockPos first = new BlockPos(4, 5, 6);
      BlockPos second = new BlockPos(-2, 3, 8);
      transaction.recordExpected(first, snapshot(first, "first"));
      transaction.recordExpected(second, snapshot(second, "second"));
      var positions = transaction.expectedPositions();
      if (!positions.hasNext() || !positions.next().equals(first)
         || !positions.hasNext() || !positions.next().equals(second) || positions.hasNext()) {
         helper.fail("transaction expected order changed");
         return;
      }
      helper.succeed();
   }

   @GameTest(template = "empty", timeoutTicks = 40)
   public static void worldRecoveryRestoresARealLevelBlock(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
      ReversibleBlockSnapshot before = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      level.setBlock(position, Blocks.STONE.defaultBlockState(), 3);
      ReversibleBlockSnapshot after = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      UUID owner = UUID.randomUUID();

      if (!WorldHistoryManager.startRollback(
         new WorldTaskContext(level.getServer(), owner),
         level.dimension(),
         new ArrayDeque<>(List.of(before)),
         Map.of(position, after),
         null
      )) {
         helper.fail("real-level recovery was not accepted");
         return;
      }

      helper.succeedWhen(() -> {
         if (level.getBlockState(position).equals(before.state()) && !WorldHistoryManager.busy(owner)) {
            helper.succeed();
         }
      });
   }

   @GameTest(template = "empty", timeoutTicks = 40)
   public static void worldRecoveryRestoresARealBlockEntity(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(2, 1, 1));
      level.setBlock(position, Blocks.CHEST.defaultBlockState(), 3);
      var before = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
      var after = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      UUID owner = UUID.randomUUID();

      if (!WorldHistoryManager.startRollback(
         new WorldTaskContext(level.getServer(), owner),
         level.dimension(),
         new ArrayDeque<>(List.of(before)),
         Map.of(position, after),
         null
      )) {
         helper.fail("block entity recovery was not accepted");
         return;
      }

      helper.succeedWhen(() -> {
         if (level.getBlockState(position).is(Blocks.CHEST)
            && level.getBlockEntity(position) != null
            && !WorldHistoryManager.busy(owner)) {
            helper.succeed();
         }
      });
   }

   @GameTest(template = "empty", timeoutTicks = 40)
   public static void worldRecoveryRestoresFluidState(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(3, 1, 1));
      level.setBlock(position, Blocks.WATER.defaultBlockState(), 3);
      var before = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
      var after = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      UUID owner = UUID.randomUUID();

      if (!WorldHistoryManager.startRollback(
         new WorldTaskContext(level.getServer(), owner),
         level.dimension(),
         new ArrayDeque<>(List.of(before)),
         Map.of(position, after),
         null
      )) {
         helper.fail("fluid recovery was not accepted");
         return;
      }

      helper.succeedWhen(() -> {
         if (level.getFluidState(position).is(Fluids.WATER)
            && !WorldHistoryManager.busy(owner)) {
            helper.succeed();
         }
      });
   }

   @GameTest(template = "empty", timeoutTicks = 40)
   public static void worldRecoveryPreservesExternalConflict(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(4, 1, 1));
      level.setBlock(position, Blocks.STONE.defaultBlockState(), 3);
      ReversibleBlockSnapshot before = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      level.setBlock(position, Blocks.DIRT.defaultBlockState(), 3);
      ReversibleBlockSnapshot after = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      UUID owner = UUID.randomUUID();

      if (!WorldHistoryManager.startRollback(
         new WorldTaskContext(level.getServer(), owner),
         level.dimension(),
         new ArrayDeque<>(List.of(before)),
         Map.of(position, after),
         null
      )) {
         helper.fail("conflict recovery was not accepted");
         return;
      }

      // A later external edit must win. Recovery should report completion once
      // it has inspected the cell and must not overwrite the external state.
      level.setBlock(position, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
      helper.succeedWhen(() -> {
         if (level.getBlockState(position).is(Blocks.GOLD_BLOCK)
            && !WorldHistoryManager.busy(owner)) {
            helper.succeed();
         }
      });
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position, String marker) {
      var tag = new net.minecraft.nbt.CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(position, null, null, new BlockEntitySnapshot(tag));
   }
}
