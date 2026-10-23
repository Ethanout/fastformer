package io.github.fastformer.fastplace.history;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.server.session.FastPlaceManager;
import io.github.fastformer.server.session.SmartFillManager;
import io.github.fastformer.network.payload.placement.SmartFillCommandPayload;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SmartFillGameTests {
   private SmartFillGameTests() { }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "smart_fill_surfaceFillUsesOneWorldUndo")
   public static void surfaceFillUsesOneWorldUndo(GameTestHelper helper) {
      var target = pit(helper, 1);
      var player = player(helper, target);
      UUID id = UUID.randomUUID();
      command(player, id, SmartFillCommandPayload.Action.DISCOVER);
      helper.assertFalse(SmartFillManager.active(player), "surface created a confirmation session");
      helper.assertTrue(FastPlaceManager.taskActive(player), "fill did not use the shared task");
      int[] phase = {0};
      helper.succeedWhen(() -> {
         helper.assertFalse(FastPlaceManager.taskActive(player), "fill task is pending");
         if (phase[0] == 0) {
            helper.assertTrue(helper.getLevel().getBlockState(target).is(Blocks.GOLD_BLOCK), "first layer was not filled");
            helper.assertTrue(helper.getLevel().getBlockState(target.below()).is(Blocks.STONE), "surface fill changed the backing wall");
            helper.assertTrue(WorldHistoryManager.requestUndo(player, 1), "undo rejected");
            phase[0] = 1;
         }
         helper.assertFalse(WorldHistoryManager.busy(player), "undo is pending");
         helper.assertTrue(helper.getLevel().getBlockState(target).isAir()
            && helper.getLevel().getBlockState(target.below()).is(Blocks.STONE), "one undo did not restore the surface");
      });
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "smart_fill_surfacePreviewKeepsCapturedMaterial")
   public static void surfacePreviewKeepsCapturedMaterial(GameTestHelper helper) {
      var target = pit(helper, 1);
      var player = playerInsideSurface(helper, target);
      UUID id = UUID.randomUUID();
      command(player, id, SmartFillCommandPayload.Action.DISCOVER);
      helper.assertTrue(SmartFillManager.active(player), "surface overlapping player did not request confirmation");
      helper.assertFalse(FastPlaceManager.taskActive(player), "preview started a world write");
      helper.assertTrue(helper.getLevel().getBlockState(target).isAir(), "preview changed the world");
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_BLOCK));
      command(player, id, SmartFillCommandPayload.Action.CONFIRM);
      command(player, id, SmartFillCommandPayload.Action.CONFIRM);
      helper.succeedWhen(() -> {
         helper.assertFalse(FastPlaceManager.taskActive(player), "fill task is pending");
         helper.assertTrue(helper.getLevel().getBlockState(target).is(Blocks.GOLD_BLOCK)
            && helper.getLevel().getBlockState(target.below()).is(Blocks.STONE), "confirmation changed material or overwrote the backing wall");
         helper.assertTrue(WorldHistoryManager.historyOrderForTest(player.getUUID(), true).size() == 1,
            "duplicate confirmation created another history entry");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "smart_fill_cancelledAndChangedPreviewsCannotWrite")
   public static void cancelledAndChangedPreviewsCannotWrite(GameTestHelper helper) {
      var target = pit(helper, 1);
      var player = playerInsideSurface(helper, target);
      UUID id = UUID.randomUUID();
      command(player, id, SmartFillCommandPayload.Action.DISCOVER);
      helper.assertTrue(SmartFillManager.active(player), "preview was not created");
      command(player, id, SmartFillCommandPayload.Action.CANCEL);
      command(player, id, SmartFillCommandPayload.Action.DISCOVER);
      command(player, id, SmartFillCommandPayload.Action.CONFIRM);
      helper.assertFalse(SmartFillManager.active(player), "cancelled id reopened a preview");
      helper.assertFalse(FastPlaceManager.taskActive(player), "cancelled preview started a write");
      id = UUID.randomUUID();
      command(player, id, SmartFillCommandPayload.Action.DISCOVER);
      helper.getLevel().setBlock(target, Blocks.WATER.defaultBlockState(), 2);
      command(player, id, SmartFillCommandPayload.Action.CONFIRM);
      helper.assertFalse(FastPlaceManager.taskActive(player), "changed preview was submitted");
      helper.assertTrue(helper.getLevel().getBlockState(target).is(Blocks.WATER), "fluid was overwritten");
      helper.assertTrue(helper.getLevel().getBlockState(target.east()).isAir(), "changed plan wrote partial blocks");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "smart_fill_playerInsideSurfaceRequiresPreview")
   public static void playerInsideSurfaceRequiresPreview(GameTestHelper helper) {
      var target = pit(helper, 1);
      var player = playerInsideSurface(helper, target);
      command(player, UUID.randomUUID(), SmartFillCommandPayload.Action.DISCOVER);
      helper.assertTrue(SmartFillManager.active(player), "player inside fill was not protected by confirmation");
      helper.assertFalse(FastPlaceManager.taskActive(player), "player inside fill started an immediate write");
      SmartFillManager.cancel(player);
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "smart_fill_actualHit")
   public static void actualBottomHitWinsOverTheNearerPitMouth(GameTestHelper helper) {
      var mouth = pit(helper, 4);
      var player = player(helper, mouth);
      command(player, UUID.randomUUID(), SmartFillCommandPayload.Action.DISCOVER);
      helper.assertTrue(FastPlaceManager.taskActive(player), "actual hit plane was not submitted");
      helper.succeedWhen(() -> {
         helper.assertFalse(FastPlaceManager.taskActive(player), "fill task is pending");
         for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            helper.assertTrue(helper.getLevel().getBlockState(mouth.offset(x, -3, z)).is(Blocks.GOLD_BLOCK),
               "plane above the actual bottom hit was not filled");
            for (int y = -2; y <= 0; y++) {
               helper.assertTrue(helper.getLevel().getBlockState(mouth.offset(x, y, z)).isAir(),
                  "a nearer plane was filled instead of the actual hit plane");
            }
         }
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "smart_fill_noFallback")
   public static void openHitPlaneDoesNotFallBackToTheClosedMouth(GameTestHelper helper) {
      var mouth = pit(helper, 4);
      // Break only the bottom-adjacent boundary; the nearer mouth stays closed.
      helper.getLevel().setBlock(mouth.offset(2, -3, 0), Blocks.AIR.defaultBlockState(), 2);
      helper.getLevel().setBlock(mouth.offset(3, -3, 0), Blocks.AIR.defaultBlockState(), 2);
      var player = player(helper, mouth);
      var settings = io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player);
      var before = settings.fillMode();
      UUID id = UUID.randomUUID();
      command(player, id, SmartFillCommandPayload.Action.DISCOVER);
      var after = io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player).fillMode();
      helper.assertTrue(after == before, "unbounded planes changed the fill mode");
      command(player, id, SmartFillCommandPayload.Action.DISCOVER);
      helper.assertTrue(io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player).fillMode() == after,
         "duplicate rejected request changed the fill mode");
      helper.assertFalse(FastPlaceManager.taskActive(player), "open hit plane fell back to another plane");
      helper.assertFalse(SmartFillManager.active(player), "open hit plane created a fallback preview");
      helper.assertTrue(helper.getLevel().getBlockState(mouth).isAir(), "nearer mouth was filled");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "smart_fill_otherPlane")
   public static void boundedPlaneAtHitPointCanDifferFromTheHitFace(GameTestHelper helper) {
      var target = helper.absolutePos(new BlockPos(3, 6, 3));
      for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) {
         helper.getLevel().setBlock(target.offset(0, y, z), Math.abs(y) == 2 || Math.abs(z) == 2
            ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
      }
      helper.getLevel().setBlock(target.below(), Blocks.STONE.defaultBlockState(), 2);
      var player = player(helper, target);
      var eye = target.getCenter().add(-3, 1, 0);
      var ray = net.minecraft.world.phys.Vec3.atLowerCornerOf(target).add(0.5, 0, 0.5).subtract(eye).normalize();
      player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
      player.setYRot((float) Math.toDegrees(Math.atan2(-ray.x, ray.z)));
      player.setYHeadRot(player.getYRot());
      player.setXRot((float) -Math.toDegrees(Math.asin(ray.y)));
      var before = io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player).fillMode();
      command(player, UUID.randomUUID(), SmartFillCommandPayload.Action.DISCOVER);
      helper.assertTrue(FastPlaceManager.taskActive(player), "bounded YZ plane was not found from an upward face hit");
      helper.assertTrue(io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player).fillMode() == before,
         "successful fill also cycled the mode");
      helper.succeedWhen(() -> {
         helper.assertFalse(FastPlaceManager.taskActive(player), "fill task is pending");
         for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            var expected = y == -1 && z == 0 ? Blocks.STONE : Blocks.GOLD_BLOCK;
            helper.assertTrue(helper.getLevel().getBlockState(target.offset(0, y, z)).is(expected),
               "fill did not match the bounded plane at the hit point");
         }
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "smart_fill_missKeepsMode")
   public static void rayMissKeepsModeWithoutStartingAWorldTask(GameTestHelper helper) {
      var player = player(helper, helper.absolutePos(new BlockPos(3, 6, 3)));
      player.setXRot(-90);
      var settings = io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player);
      var before = settings.fillMode();
      command(player, UUID.randomUUID(), SmartFillCommandPayload.Action.DISCOVER);
      helper.assertTrue(io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player).fillMode() == before,
         "ray miss changed the fill mode");
      helper.assertFalse(FastPlaceManager.taskActive(player), "ray miss started a world task");
      helper.assertFalse(SmartFillManager.active(player), "ray miss created a fill preview");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "smart_fill_airOnlyTaskRetainsPlantsAndFluidsAndFinishes")
   public static void airOnlyTaskRetainsPlantsAndFluidsAndFinishes(GameTestHelper helper) {
      var target = helper.absolutePos(new BlockPos(2, 2, 2));
      var player = player(helper, target);
      var plant = target.east(2);
      var fluid = target.west(2);
      helper.getLevel().setBlock(target, Blocks.AIR.defaultBlockState(), 2);
      helper.getLevel().setBlock(plant.below(), Blocks.DIRT.defaultBlockState(), 2);
      helper.getLevel().setBlock(plant, Blocks.SHORT_GRASS.defaultBlockState(), 2);
      for (var side : net.minecraft.core.Direction.values()) {
         helper.getLevel().setBlock(fluid.relative(side), Blocks.STONE.defaultBlockState(), 2);
      }
      helper.getLevel().setBlock(fluid, Blocks.WATER.defaultBlockState(), 2);
      helper.assertTrue(FastPlaceManager.queueAirFill(player, java.util.Set.of(target, plant, fluid),
         Blocks.GOLD_BLOCK.defaultBlockState()), "air-only task was rejected");
      helper.succeedWhen(() -> {
         helper.assertFalse(FastPlaceManager.taskActive(player), "air-only skipped cells did not finish");
         helper.assertTrue(helper.getLevel().getBlockState(target).is(Blocks.GOLD_BLOCK), "air was not filled");
         helper.assertTrue(helper.getLevel().getBlockState(plant).is(Blocks.SHORT_GRASS), "plant was overwritten");
         helper.assertTrue(helper.getLevel().getBlockState(fluid).is(Blocks.WATER), "fluid was overwritten");
      });
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "smart_fill_cube")
   public static void cubeWallsCanBeFilledInSequenceThroughRealRaycasts(GameTestHelper helper) {
      var base = helper.absolutePos(new BlockPos(2, 8, 2));
      // Mock players do not load their surroundings. Include the air beside
      // the frame, which the search must read without loading chunks itself.
      for (int x = (base.getX() - 3) >> 4; x <= (base.getX() + 9) >> 4; x++) {
         for (int z = (base.getZ() - 3) >> 4; z <= (base.getZ() + 9) >> 4; z++) helper.getLevel().getChunk(x, z);
      }
      for (int x = 0; x <= 6; x++) for (int y = 0; y <= 6; y++) for (int z = 0; z <= 6; z++) {
         int edges = (x == 0 || x == 6 ? 1 : 0) + (y == 0 || y == 6 ? 1 : 0) + (z == 0 || z == 6 ? 1 : 0);
         helper.getLevel().setBlock(base.offset(x, y, z), edges >= 2 || x == 6
            ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
      }
      var player = player(helper, base);
      var faces = java.util.List.of(base.offset(0, 3, 3), base.offset(3, 3, 0), base.offset(3, 3, 6),
         base.offset(3, 0, 3), base.offset(3, 6, 3));
      var outward = java.util.List.of(net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.NORTH,
         net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.DOWN, net.minecraft.core.Direction.UP);
      int[] next = {0};
      BlockPos[] backing = {null};
      helper.succeedWhen(() -> {
         helper.assertFalse(FastPlaceManager.taskActive(player), "previous face fill is pending");
         if (backing[0] != null) {
            helper.getLevel().setBlock(backing[0], Blocks.AIR.defaultBlockState(), 2);
            backing[0] = null;
         }
         if (next[0] < faces.size()) {
            var face = outward.get(next[0]);
            var ray = net.minecraft.world.phys.Vec3.atLowerCornerOf(face.getOpposite().getNormal());
            backing[0] = faces.get(next[0]).relative(face.getOpposite());
            helper.getLevel().setBlock(backing[0], Blocks.STONE.defaultBlockState(), 2);
            var center = net.minecraft.world.phys.Vec3.atLowerCornerOf(faces.get(next[0])).add(0.5, 0.5, 0.5);
            var eye = center.subtract(ray.scale(4));
            player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
            player.setYRot((float) Math.toDegrees(Math.atan2(-ray.x, ray.z)));
            player.setYHeadRot(player.getYRot());
            player.setXRot((float) -Math.toDegrees(Math.asin(ray.y)));
            command(player, UUID.randomUUID(), SmartFillCommandPayload.Action.DISCOVER);
            helper.assertFalse(SmartFillManager.active(player), "frame face unexpectedly required a preview");
            helper.assertTrue(FastPlaceManager.taskActive(player), "cube face " + next[0] + " was not recognized; hit="
               + io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast.clip(
                  player.level(), player, player.getEyePosition(), player.getViewVector(1)).hit().getBlockPos()
               + "; world busy=" + io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(
                  player.getServer(), player.level().dimension()));
            next[0]++;
            helper.assertTrue(false, "waiting for face fill");
         }
         for (int x = 0; x <= 6; x++) for (int y = 0; y <= 6; y++) for (int z = 0; z <= 6; z++) {
            var state = helper.getLevel().getBlockState(base.offset(x, y, z));
            boolean surface = x == 0 || x == 6 || y == 0 || y == 6 || z == 0 || z == 6;
            helper.assertTrue(surface ? !state.isAir() : state.isAir(), "cube shell or interior does not match the preview");
         }
      });
   }

   private static BlockPos pit(GameTestHelper helper, int depth) {
      var top = helper.absolutePos(new BlockPos(3, 6, 3));
      // F targets the plane immediately above the bottom hit, not the mouth.
      for (int y = -depth; y <= 3; y++) for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
         boolean stone = y == -depth || y <= 0 && (Math.abs(x) >= 2 || Math.abs(z) >= 2);
         helper.getLevel().setBlock(top.offset(x, y, z), stone ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
      }
      return top;
   }
   private static ServerPlayer player(GameTestHelper helper, BlockPos target) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000);
      player.setGameMode(GameType.CREATIVE);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_BLOCK));
      player.setPos(target.getCenter().add(0, 2, 0));
      player.setXRot(90);
      return player;
   }
   private static ServerPlayer playerInsideSurface(GameTestHelper helper, BlockPos target) {
      var player = player(helper, target);
      player.setPos(target.getCenter().add(0, -0.25, 0));
      return player;
   }
   private static void command(ServerPlayer player, UUID id, SmartFillCommandPayload.Action action) {
      SmartFillManager.handle(player, new SmartFillCommandPayload(id, player.level().dimension().location(), action,
         player.getEyePosition(), player.getViewVector(1)));
   }
}
