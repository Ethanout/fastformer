package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;

@GameTestHolder(FastFormer.MOD_ID)
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class WorldChangeBatchCodecGameTests {
   private WorldChangeBatchCodecGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100)
   public static void compactLayoutsRoundTrip(GameTestHelper helper) throws Exception {
      UUID operationId = UUID.randomUUID();
      BlockPos first = new BlockPos(-3, 20, 7);
      BlockPos second = first.east();
      CompoundTag entityData = new CompoundTag();
      entityData.putString("CustomName", "codec-test");
      WorldChangeBatch dense = batch(
         helper,
         List.of(
            snapshot(first, Blocks.STONE.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), entityData),
            snapshot(second, Blocks.DIRT.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null)
         ),
         Map.of(
            first, snapshot(first, Blocks.WATER.defaultBlockState(), Fluids.WATER.getFlowing(3, false), null),
            second, snapshot(second, Blocks.WATER.defaultBlockState(), Fluids.WATER.getSource(false), entityData)
         )
      ).withOperationId(operationId);

      WorldChangeBatch decodedDense = WorldChangeBatch.decode(helper.getLevel().registryAccess(), dense.encode());
      assertEquivalent(helper, dense, decodedDense);
      helper.assertTrue(decodedDense.operationId().equals(operationId), "operation ID was not preserved");
      helper.assertTrue(!decodedDense.encode().contains("Positions"), "dense layout expanded into sparse positions");

      BlockPos far = first.offset(40, 0, 0);
      WorldChangeBatch sparse = batch(
         helper,
         List.of(
            snapshot(first, Blocks.STONE.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null),
            snapshot(far, Blocks.DIRT.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null)
         ),
         Map.of(
            first, snapshot(first, Blocks.GOLD_BLOCK.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null),
            far, snapshot(far, Blocks.WATER.defaultBlockState(), Fluids.WATER.getSource(false), null)
         )
      );
      WorldChangeBatch decodedSparse = WorldChangeBatch.decode(helper.getLevel().registryAccess(), sparse.encode());
      assertEquivalent(helper, sparse, decodedSparse);
      helper.assertTrue(decodedSparse.encode().contains("Positions"), "sparse layout was not preserved");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100)
   public static void malformedPayloadsAreRejected(GameTestHelper helper) throws Exception {
      BlockPos pos = new BlockPos(1, 2, 3);
      WorldChangeBatch batch = batch(
         helper,
         List.of(snapshot(pos, Blocks.STONE.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null)),
         Map.of(pos, snapshot(pos, Blocks.WATER.defaultBlockState(), Fluids.WATER.getSource(false), null))
      );
      CompoundTag valid = batch.encode();

      CompoundTag wrongVolume = valid.copy();
      wrongVolume.getCompound("Dense").putInt("Width", 2);
      expectDecodeFailure(helper, wrongVolume, "dense volume mismatch");

      CompoundTag badId = valid.copy();
      badId.putInt("BeforeBlockUniformId", 1000);
      expectDecodeFailure(helper, badId, "out-of-range palette ID");

      CompoundTag unknownFluid = valid.copy();
      unknownFluid.getList("FluidPalette", Tag.TAG_COMPOUND)
         .getCompound(0).putString("Name", "fastformer:missing_fluid");
      expectDecodeFailure(helper, unknownFluid, "unknown fluid name");

      CompoundTag duplicateLayout = valid.copy();
      duplicateLayout.putLongArray("Positions", new long[]{pos.asLong()});
      expectDecodeFailure(helper, duplicateLayout, "ambiguous position layout");
      helper.succeed();
   }

   private static WorldChangeBatch batch(
      GameTestHelper helper,
      List<ReversibleBlockSnapshot> before,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      return WorldChangeBatch.capturePairsByPos(helper.getLevel().dimension(), before, new HashMap<>(after)).orElseThrow();
   }

   private static ReversibleBlockSnapshot snapshot(
      BlockPos pos, BlockState block, FluidState fluid, CompoundTag blockEntity
   ) {
      return new ReversibleBlockSnapshot(
         pos, block, fluid, blockEntity == null ? null : new BlockEntitySnapshot(blockEntity)
      );
   }

   private static void assertEquivalent(GameTestHelper helper, WorldChangeBatch expected, WorldChangeBatch actual) {
      helper.assertTrue(expected.dimension().equals(actual.dimension()), "dimension was not preserved");
      helper.assertTrue(expected.size() == actual.size(), "batch size was not preserved");
      for (int index = 0; index < expected.size(); index++) {
         helper.assertTrue(expected.position(index).equals(actual.position(index)), "position was not preserved");
         helper.assertTrue(
            expected.sourceSnapshots(true).get(index).sameContents(actual.sourceSnapshots(true).get(index)),
            "after snapshot was not preserved"
         );
         helper.assertTrue(
            expected.targetSnapshots(true).get(index).sameContents(actual.targetSnapshots(true).get(index)),
            "before snapshot was not preserved"
         );
      }
   }

   private static void expectDecodeFailure(GameTestHelper helper, CompoundTag payload, String caseName) {
      try {
         WorldChangeBatch.decode(helper.getLevel().registryAccess(), payload);
         helper.assertTrue(false, "accepted malformed payload: " + caseName);
      } catch (IOException expected) {
         // Expected boundary failure.
      }
   }
}
