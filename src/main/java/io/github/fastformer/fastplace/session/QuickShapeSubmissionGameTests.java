package io.github.fastformer.fastplace.session;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.FastPlaceManager;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.ServerInputDispatcher;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuickShapeSubmissionGameTests {
   private QuickShapeSubmissionGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_submission", timeoutTicks = 20000)
   public static void scrollEnterWritesTheCandidateLine(GameTestHelper helper) {
      submit(helper, LineMode.FREE_SCROLL, true);
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_submission", timeoutTicks = 20000)
   public static void ordinaryEnterWritesOnlyTheConfirmedPoint(GameTestHelper helper) {
      submit(helper, LineMode.AXIS, false);
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_middle", timeoutTicks = 600)
   public static void polygonMiddleDoesNotAddAPointOrSubmit(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(-90.0F);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.GOLD_BLOCK));
      FastPlaceSettings.load(player).setMode(player, FaceMode.POLYGON);
      BlockPos first = helper.absolutePos(new BlockPos(1, 3, 1));
      FastPlaceManager.addPoint(player, first, first);
      FastPlaceSession session = FastPlaceManager.session(player).orElseThrow();
      session.addPoint(first.offset(3, 0, 0), player.getEyePosition(), player.getViewVector(1.0F));
      var confirmed = java.util.List.copyOf(session.points());
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for the write gate");
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "unexpected input gate");
         ServerInputDispatcher.quickShape(player);
         helper.assertTrue(FastPlaceManager.active(player), "middle ended the polygon draft");
         helper.assertTrue(confirmed.equals(session.points()), "middle changed polygon points");
         helper.assertFalse(FastPlaceManager.taskActive(player), "middle submitted the polygon");
         FastPlaceManager.cancel(player);
      });
   }

   private static void submit(GameTestHelper helper, LineMode mode, boolean includesCandidate) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.GOLD_BLOCK));
      FastPlaceSettings.load(player).setMode(player, mode);
      BlockPos first = helper.absolutePos(new BlockPos(1, 3, 1));
      for (int x = 0; x <= 3; x++) {
         helper.getLevel().setBlock(first.offset(x, 0, 0), Blocks.AIR.defaultBlockState(), 2);
      }
      FastPlaceManager.addPoint(player, first, first);
      FastPlaceManager.session(player).orElseThrow().setFreeScrollOffset(new BlockPos(3, 0, 0));
      helper.succeedWhen(() -> {
         if (FastPlaceManager.active(player)) {
            FastPlaceManager.fill(player);
            helper.assertFalse(FastPlaceManager.active(player), "waiting for submission admission");
         }
         FastPlaceManager.tickWorld(helper.getLevel().getServer());
         helper.assertFalse(FastPlaceManager.taskActive(player), "placement has not finished");
         helper.assertTrue(helper.getLevel().getBlockState(first).is(Blocks.GOLD_BLOCK), "confirmed point was not placed");
         for (int x = 1; x <= 3; x++) {
            helper.assertTrue(
               helper.getLevel().getBlockState(first.offset(x, 0, 0)).is(includesCandidate ? Blocks.GOLD_BLOCK : Blocks.AIR),
               "submission used the wrong candidate policy at offset " + x
            );
         }
      });
   }
}
