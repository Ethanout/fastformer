package io.github.fastformer.client.operation.model;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
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

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void samePositionWithDifferentFacingRemainsAPart(GameTestHelper helper) {
      var north = Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
      var original = selection(new ClientBlockSnapshot(north, null));
      var rotated = original.withTransform(original.transform().withRotation(new Vec3(0, Math.PI, 0)));
      helper.assertTrue(!rotated.isOriginalSelection(), "different facing restored selection identity");
      helper.assertTrue(!rotated.canAdjustGeometry(), "transformed facing allowed face editing");
      var restored = rotated.withTransform(WorkspaceTransform.IDENTITY);
      helper.assertTrue(original.equals(restored), "inverse rotation did not restore the selection");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void containerContentsParticipateInSelectionIdentity(GameTestHelper helper) {
      var position = helper.absolutePos(new BlockPos(1, 1, 1));
      var level = helper.getLevel();
      level.setBlock(position, Blocks.CHEST.defaultBlockState(), 2);
      var chest = (ChestBlockEntity) level.getBlockEntity(position);
      chest.setItem(0, new ItemStack(Items.DIAMOND, 1));
      var before = new ClientBlockSnapshot(level.getBlockState(position), chest.saveWithoutMetadata(level.registryAccess()));
      var original = selection(before);
      var moved = original.withTranslation(new Vec3(4, 0, 0));

      chest.setItem(0, new ItemStack(Items.DIAMOND, 2));
      var after = new ClientBlockSnapshot(level.getBlockState(position), chest.saveWithoutMetadata(level.registryAccess()));
      var changed = moved.withBlocks(Map.of(BlockPos.ZERO, after)).withTranslation(Vec3.ZERO);
      helper.assertTrue(!changed.isOriginalSelection(), "different chest contents restored selection identity");
      helper.assertTrue(changed.baseline().sourceSnapshot().get(BlockPos.ZERO).equals(before), "baseline changed with the world");
      var restored = changed.withBlocks(Map.of(BlockPos.ZERO, before));
      helper.assertTrue(original.equals(restored), "original chest contents did not restore selection identity");
      helper.assertTrue(chest.getItem(0).getCount() == 2, "client model changed the world chest");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void symmetricStoneRotationRestoresAllSelectionCapabilities(GameTestHelper helper) {
      var original = selection(new ClientBlockSnapshot(Blocks.STONE.defaultBlockState(), null));
      var restored = original.withTransform(original.transform().withRotation(new Vec3(0, Math.PI, 0)));
      helper.assertTrue(original.equals(restored), "symmetric rotation retained a transformed part");
      helper.assertTrue(restored.canAdjustGeometry(), "restored selection cannot edit faces");
      helper.assertTrue(!restored.masksSourceBlocks(), "restored selection still masks its source");
      helper.succeed();
   }

   private static ClientSelectionPart selection(ClientBlockSnapshot snapshot) {
      var bounds = OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO);
      return new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, bounds,
         Map.of(BlockPos.ZERO, snapshot), WorkspaceTransform.IDENTITY, false);
   }
}
