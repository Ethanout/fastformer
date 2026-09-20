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

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_snapshot")
   public static void submissionParametersPreserveRegisteredBlockState(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var value = new io.github.fastformer.network.payload.preview.QuickShapeSubmissionParametersPayload(
         12, 789, Blocks.OAK_LOG.defaultBlockState(), io.github.fastformer.fastplace.geometry.generation.LineTieBias.OPPOSITE,
         io.github.fastformer.network.sync.PlayerPreviewSync.callbackScope(player));
      var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
      try {
         var codec = io.github.fastformer.network.payload.preview.QuickShapeSubmissionParametersPayload.STREAM_CODEC;
         codec.encode(buffer, value);
         helper.assertTrue(value.equals(codec.decode(buffer)), "submission parameters lost their registry state or limit");
      } finally {
         buffer.release();
      }
      helper.succeed();
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

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_snapshot", timeoutTicks = 20000)
   public static void staleConfirmationCannotSubmitANewerDraft(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      player.setPos(helper.absolutePos(new BlockPos(1, 30, 1)).getCenter());
      player.setXRot(-90.0F);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.GOLD_BLOCK));
      FastPlaceSettings.load(player).setMode(player, LineMode.FREE_SCROLL);
      BlockPos first = helper.absolutePos(new BlockPos(1, 3, 1));
      helper.getLevel().setBlock(first, Blocks.AIR.defaultBlockState(), 2);
      FastPlaceManager.addPoint(player, first, first);
      long oldRevision = io.github.fastformer.network.sync.PlayerPreviewSync.buildingRevision(player);
      var scope = io.github.fastformer.network.sync.PlayerPreviewSync.callbackScope(player);
      var session = FastPlaceManager.session(player).orElseThrow();
      session.setFreeScrollOffset(new BlockPos(2, 0, 0));
      io.github.fastformer.network.sync.PlayerPreviewSync.syncPreview(player, session);
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for the write gate");
         if (FastPlaceManager.active(player)) {
            ServerInputDispatcher.confirmQuickShape(player,
               new io.github.fastformer.network.payload.placement.QuickShapeConfirmPayload(40, oldRevision, scope));
            helper.assertTrue(FastPlaceManager.active(player), "stale request ended the newer draft");
            helper.assertFalse(FastPlaceManager.taskActive(player), "stale request created a world task");
            helper.assertTrue(helper.getLevel().getBlockState(first).isAir(), "stale request wrote the world");
            long revision = io.github.fastformer.network.sync.PlayerPreviewSync.buildingRevision(player);
            ServerInputDispatcher.confirmQuickShape(player,
               new io.github.fastformer.network.payload.placement.QuickShapeConfirmPayload(41, revision, scope));
            helper.assertFalse(FastPlaceManager.active(player), "current request was not admitted");
         }
         FastPlaceManager.tickWorld(helper.getLevel().getServer());
         helper.assertFalse(FastPlaceManager.taskActive(player), "placement has not finished");
         helper.assertTrue(helper.getLevel().getBlockState(first).is(Blocks.GOLD_BLOCK), "current request did not write");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_policy")
   public static void changedMaterialInvalidatesPublishedSubmissionParameters(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.GOLD_BLOCK));
      BlockPos first = helper.absolutePos(new BlockPos(1, 3, 1));
      FastPlaceManager.addPoint(player, first, first);
      helper.assertTrue(io.github.fastformer.network.sync.PlayerPreviewSync.buildingSubmissionParametersMatch(player),
         "published parameters do not match their original material");
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.IRON_BLOCK));
      helper.assertFalse(io.github.fastformer.network.sync.PlayerPreviewSync.buildingSubmissionParametersMatch(player),
         "old parameters accepted a different material");
      io.github.fastformer.network.sync.PlayerPreviewSync.syncPreview(player, FastPlaceManager.session(player).orElseThrow());
      helper.assertTrue(io.github.fastformer.network.sync.PlayerPreviewSync.buildingSubmissionParametersMatch(player),
         "refreshed parameters rejected the new material");
      io.github.fastformer.network.sync.PlayerPreviewSync.forgetActivity(player);
      helper.assertFalse(io.github.fastformer.network.sync.PlayerPreviewSync.buildingSubmissionParametersMatch(player),
         "forgotten session retained submission parameters");
      FastPlaceManager.cancel(player);
      helper.succeed();
   }
}
