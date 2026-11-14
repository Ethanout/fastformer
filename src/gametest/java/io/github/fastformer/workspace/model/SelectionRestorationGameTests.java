package io.github.fastformer.workspace.model;

import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;

import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.client.preview.block.BlockPreviewInput;
import io.github.fastformer.client.preview.block.BlockPreviewMode;
import io.github.fastformer.client.preview.block.BlockPreviewModel;

import io.github.fastformer.FastFormer;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SelectionRestorationGameTests {
   private SelectionRestorationGameTests() { }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 20)
   public static void blockSnapshotCopiesNestedDataAtConstructionAndAccess(GameTestHelper helper) {
      var nested = new net.minecraft.nbt.CompoundTag();
      nested.putInt("value", 7);
      var original = new net.minecraft.nbt.CompoundTag();
      original.put("nested", nested);
      var snapshot = stoneSnapshot(original);

      nested.putInt("value", 9);
      original.putBoolean("addedAfterCapture", true);
      helper.assertTrue(snapshot.blockEntity().getCompound("nested").getInt("value") == 7,
         "caller mutated stored nested block entity data");
      helper.assertFalse(snapshot.blockEntity().contains("addedAfterCapture"),
         "caller added data to the captured block entity");

      var returned = snapshot.blockEntity();
      returned.getCompound("nested").putInt("value", 11);
      returned.remove("nested");
      helper.assertTrue(snapshot.blockEntity().getCompound("nested").getInt("value") == 7,
         "accessor exposed mutable stored block entity data");
      helper.assertTrue(snapshot.state() == Blocks.STONE.defaultBlockState(), "snapshot changed the immutable block state");
      helper.assertTrue(stoneSnapshot(null).blockEntity() == null, "absent block entity became nonnull");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 20)
   public static void blockPreviewCapturesImmutableContentAndKeepsToolsIndependent(GameTestHelper helper) {
      var blocks = new java.util.HashMap<BlockPos, BlockSnapshot>();
      blocks.put(BlockPos.ZERO, stoneSnapshot(null));
      var input = new BlockPreviewInput(blocks);
      blocks.clear();
      helper.assertTrue(input.blocks().size() == 1, "captured input followed the mutable source map");
      var fill = new BlockPreviewMode(0.55F).buildModel(input);
      var replacement = new BlockPreviewMode(0.42F).buildModel(BlockPreviewInput.EMPTY);
      helper.assertTrue(fill.blocks().size() == 1, "a different tool cleared captured fill content");
      helper.assertTrue(replacement == BlockPreviewModel.EMPTY, "empty replacement allocated a content model");
      try {
         fill.blocks().clear();
         helper.fail("preview exposed mutable captured content");
         return;
      } catch (UnsupportedOperationException expected) {
         // Captured preview maps are immutable to their consumers.
      }
      helper.succeed();
   }

   private static BlockSnapshot stoneSnapshot(net.minecraft.nbt.CompoundTag data) {
      return new BlockSnapshot(Blocks.STONE.defaultBlockState(), data);
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void samePositionWithDifferentFacingRemainsAPart(GameTestHelper helper) {
      var north = Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
      var original = selection(new BlockSnapshot(north, null));
      var rotated = original.withTransform(original.transform().withRotation(new Vec3(0, Math.PI, 0)));
      helper.assertTrue(!rotated.isOriginalSelection(), "different facing restored selection identity");
      helper.assertTrue(!rotated.canAdjustGeometry(), "transformed facing allowed face editing");
      var restored = rotated.withTransform(WorkspaceTransform.IDENTITY);
      helper.assertFalse(restored.canAdjustGeometry(), "inverse rotation unlocked the fixed selection");
      helper.assertTrue(restored.transform().equals(WorkspaceTransform.IDENTITY), "inverse rotation changed the transform");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void containerContentsParticipateInSelectionIdentity(GameTestHelper helper) {
      var position = helper.absolutePos(new BlockPos(1, 1, 1));
      var level = helper.getLevel();
      level.setBlock(position, Blocks.CHEST.defaultBlockState(), 2);
      var chest = (ChestBlockEntity) level.getBlockEntity(position);
      chest.setItem(0, new ItemStack(Items.DIAMOND, 1));
      var before = new BlockSnapshot(level.getBlockState(position), chest.saveWithoutMetadata(level.registryAccess()));
      var original = selection(before);
      var moved = original.withTranslation(new Vec3(4, 0, 0));

      chest.setItem(0, new ItemStack(Items.DIAMOND, 2));
      var after = new BlockSnapshot(level.getBlockState(position), chest.saveWithoutMetadata(level.registryAccess()));
      var changed = moved.withBlocks(Map.of(BlockPos.ZERO, after)).withTranslation(Vec3.ZERO);
      helper.assertTrue(!changed.isOriginalSelection(), "different chest contents restored selection identity");
      helper.assertTrue(changed.baseline().sourceSnapshot().get(BlockPos.ZERO).equals(before), "baseline changed with the world");
      var restored = changed.withBlocks(Map.of(BlockPos.ZERO, before));
      helper.assertFalse(restored.isOriginalSelection(), "original chest contents unlocked the fixed selection");
      helper.assertTrue(restored.baseline().sourceSnapshot().get(BlockPos.ZERO).equals(before), "fixed selection lost its baseline");
      helper.assertTrue(chest.getItem(0).getCount() == 2, "client model changed the world chest");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void symmetricStoneRotationKeepsSelectionFixed(GameTestHelper helper) {
      var original = selection(new BlockSnapshot(Blocks.STONE.defaultBlockState(), null));
      var restored = original.withTransform(original.transform().withRotation(new Vec3(0, Math.PI, 0)));
      helper.assertFalse(restored.isOriginalSelection(), "symmetric rotation unlocked the selection");
      helper.assertFalse(restored.canAdjustGeometry(), "fixed selection allowed face editing");
      helper.assertTrue(restored.masksSourceBlocks(), "fixed transformed selection lost its source mask");
      helper.succeed();
   }

   private static ClientSelectionPart selection(BlockSnapshot snapshot) {
      var bounds = OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO);
      return new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, bounds,
         Map.of(BlockPos.ZERO, snapshot), WorkspaceTransform.IDENTITY, false);
   }
}
