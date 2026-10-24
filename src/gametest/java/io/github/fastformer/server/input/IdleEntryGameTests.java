package io.github.fastformer.server.input;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.geometry.raycast.InteractionRaycastRules;
import io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.network.payload.placement.IdleInteractionCapturePayload;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;
import io.github.fastformer.network.sync.PlayerPreviewSync;
import io.github.fastformer.server.session.FastPlaceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IdleEntryGameTests {
   private IdleEntryGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void ordinaryClicksDoNotCreateAPointDraft(GameTestHelper helper) {
      var player = player(helper);
      var hit = hit(player);
      helper.assertFalse(ServerInputDispatcher.rightClickBlock(player, hit), "ordinary use started a building draft");
      helper.assertFalse(ServerInputDispatcher.rightClickItem(player), "ordinary item use started a building draft");
      helper.assertFalse(ServerInputDispatcher.leftClickBlock(player, hit.getBlockPos()), "ordinary attack started a selection");
      ServerInputDispatcher.middleClick(player, hit.getBlockPos());
      helper.assertTrue(IdleInteractionSnapshots.idle(player), "ordinary click changed the idle session");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void secondPressUsesSnapshotAfterTheWorldChanges(GameTestHelper helper) {
      var player = player(helper);
      var hit = hit(player);
      IdleInteractionSnapshots.capture(player, new IdleInteractionCapturePayload(101, hit, player.getEyePosition(), player.getViewVector(1)));
      helper.getLevel().setBlock(hit.getBlockPos(), Blocks.AIR.defaultBlockState(), 2);
      helper.getLevel().setBlock(hit.getBlockPos().above(), Blocks.STONE.defaultBlockState(), 2);
      helper.assertTrue(!hit(player).getBlockPos().equals(hit.getBlockPos()), "occluder did not block the ordinary ray");
      ServerInputDispatcher.startPlacement(player, request(player, 101, hit));
      var draft = FastPlaceManager.session(player).orElseThrow();
      helper.assertTrue(draft.points().equals(java.util.List.of(hit.getBlockPos().above())), "entry lost the first press's surface placement rule");
      FastPlaceManager.cancel(player);
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void successfulPlacementKeepsThePreInteractionHitShape(GameTestHelper helper) {
      var player = player(helper);
      helper.succeedWhen(() -> {
      helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(
         player.getServer(), player.level().dimension()), "waiting for world writer");
      helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for history");
      player.setPos(helper.absolutePos(new BlockPos(2, 2, 2)).getCenter().add(0, 2, 0));
      player.setXRot(90);
      var first = hit(player);
      IdleInteractionSnapshots.capture(player, new IdleInteractionCapturePayload(102, first, player.getEyePosition(), player.getViewVector(1)));
      var result = player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, first);
      helper.assertTrue(result.consumesAction(), "first placement was not consumed");
      var placed = first.getBlockPos().above();
      helper.assertTrue(helper.getLevel().getBlockState(placed).is(Blocks.OAK_LOG), "first placement did not produce a log: hit="
         + first.getBlockPos() + "/" + first.getDirection() + "; above=" + helper.getLevel().getBlockState(placed)
         + "; allowed=" + player.level().mayInteract(player, placed) + "; reach=" + player.canInteractWithBlock(placed, 0));
      var second = hit(player);
      helper.assertTrue(second.getBlockPos().equals(placed), "second ray did not hit the placed shape");
      ServerInputDispatcher.startPlacement(player, request(player, 102, second));
      helper.assertTrue(FastPlaceManager.session(player).orElseThrow().points().equals(java.util.List.of(placed)),
         "double-click lost the captured placement anchor");
      helper.assertTrue(helper.getLevel().getBlockState(placed.above()).isAir(), "entry placed a second log");
      FastPlaceManager.cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void doubleClickKeepsTheFirstPlacedStateThroughWorldAndViewChanges(GameTestHelper helper) {
      var player = player(helper);
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_STAIRS));
      helper.succeedWhen(() -> {
         helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for history");
         helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(
            player.getServer(), player.level().dimension()), "waiting for world writer");
         player.setPos(helper.absolutePos(new BlockPos(2, 2, 2)).getCenter().add(0, 2, 0));
         player.setXRot(90);
         var first = hit(player);
         IdleInteractionSnapshots.capture(player, new IdleInteractionCapturePayload(105, first, player.getEyePosition(), player.getViewVector(1)));
         var result = player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, first);
         helper.assertTrue(result.consumesAction(), "first stair placement failed");
         var placed = first.getBlockPos().above();
         var expected = helper.getLevel().getBlockState(placed);
         helper.assertTrue(expected.is(Blocks.OAK_STAIRS), "first interaction did not place stairs: hit=" + first.getBlockPos()
            + "/" + first.getDirection() + "; above=" + expected + "; allowed=" + player.level().mayInteract(player, placed)
            + "; reach=" + player.canInteractWithBlock(placed, 0));
         var second = hit(player);
         player.setYRot(180);
         player.setXRot(88);
         helper.getLevel().setBlock(placed, Blocks.STONE.defaultBlockState(), 2);
         ServerInputDispatcher.startPlacement(player, request(player, 105, second));
         var draft = FastPlaceManager.session(player).orElseThrow();
         var placement = draft.placementContext();
         helper.assertTrue(draft.points().equals(java.util.List.of(placed)), "double-click lost the captured placement anchor");
         helper.assertTrue(io.github.fastformer.fastplace.placement.context.PlaceableItems.placementState(
            player.getMainHandItem(), player, placement).orElseThrow().equals(expected), "second click recalculated the first placed state");
         helper.assertTrue(io.github.fastformer.fastplace.placement.context.PlaceableItems.placementState(
            new ItemStack(Items.STONE), player, placement).orElseThrow().is(Blocks.STONE), "changing material reused the old stair state");
         var payload = new io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload(true, true, true,
            false, false, false, io.github.fastformer.fastplace.quickshape.PolygonVolumeShape.EXTRUDE,
            draft.points(), BlockPos.ZERO, placement);
         var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
         try {
            io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload.STREAM_CODEC.encode(buffer, payload);
            var decoded = io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload.STREAM_CODEC.decode(buffer);
            helper.assertTrue(decoded.value().placementContext().equals(placement), "client preview lost the captured block state");
         } finally {
            buffer.release();
         }
         FastPlaceManager.cancel(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void changingTheHeldItemInvalidatesTheSnapshot(GameTestHelper helper) {
      var player = player(helper);
      var hit = hit(player);
      IdleInteractionSnapshots.capture(player, new IdleInteractionCapturePayload(103, hit, player.getEyePosition(), player.getViewVector(1)));
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
      ServerInputDispatcher.startPlacement(player, request(player, 103, hit));
      helper.assertFalse(FastPlaceManager.active(player), "old snapshot survived a held-item change");
      helper.succeed();
   }

   private static ServerPlayer player(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_LOG));
      player.setPos(pos.getCenter().add(0, 2, 0));
      player.setXRot(90);
      PlayerPreviewSync.syncSettings(player);
      return player;
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void doubleClickUnderWaterKeepsTheFirstPlacementRules(GameTestHelper helper) {
      var player = player(helper);
      var level = helper.getLevel();
      var floor = hit(player).getBlockPos();
      var placed = floor.above();
      level.setBlock(placed, Blocks.WATER.defaultBlockState(), 2);
      level.setBlock(placed.above(), Blocks.WATER.defaultBlockState(), 2);
      player.setPos(floor.getCenter().add(0, 4, 0));
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var rules = io.github.fastformer.fastplace.geometry.raycast.InteractionRaycastRules.VANILLA_OUTLINE;
      var first = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.clip(player, eye, view, rules.throughFluids());
      helper.assertTrue(first.getBlockPos().equals(floor), "first placement ray did not pass through water");
      IdleInteractionSnapshots.capture(player, new IdleInteractionCapturePayload(120, first, eye, view, rules));
      // A second click's different policy must not replace the policy stored before the first use.
      level.setBlock(placed, Blocks.OAK_LOG.defaultBlockState(), 2);
      IdleInteractionSnapshots.placed(player, placed);
      var second = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.clip(player, eye, view, false);
      helper.assertTrue(second.getBlockPos().equals(placed.above()), "second ray should hit the water above the placed block");
      ServerInputDispatcher.startPlacement(player, request(player, 120, second));
      var draft = FastPlaceManager.session(player).orElseThrow();
      helper.assertTrue(draft.points().equals(java.util.List.of(placed)), "double-click moved the first point onto the water surface");
      helper.assertTrue(draft.placementContext().capturedState().state().is(Blocks.OAK_LOG), "first placed state was lost");
      FastPlaceManager.cancel(player);
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void doubleClickKeepsSurfaceOrEmbeddedRuleWithoutAPlacedBlock(GameTestHelper helper) {
      var player = player(helper);
      var first = hit(player);
      for (boolean embedded : new boolean[]{false, true}) {
         var rules = new io.github.fastformer.fastplace.geometry.raycast.InteractionRaycastRules(embedded, true);
         long token = embedded ? 122 : 121;
         IdleInteractionSnapshots.capture(player, new IdleInteractionCapturePayload(token, first, player.getEyePosition(), player.getViewVector(1), rules));
         ServerInputDispatcher.startPlacement(player, request(player, token, first));
         var expected = embedded ? first.getBlockPos() : first.getBlockPos().above();
         helper.assertTrue(FastPlaceManager.session(player).orElseThrow().points().equals(java.util.List.of(expected)),
            "second click changed embedded=" + embedded);
         FastPlaceManager.cancel(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void emptyHandDoubleClickUsesTheVanillaBlackOutlineBehindWater(GameTestHelper helper) {
      var player = player(helper);
      var floor = hit(player).getBlockPos();
      var level = helper.getLevel();
      level.setBlock(floor.above(), Blocks.WATER.defaultBlockState(), 2);
      level.setBlock(floor.above(2), Blocks.WATER.defaultBlockState(), 2);
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(20);
      player.setPos(floor.getCenter().add(0, 4, 0));
      var blackOutline = (net.minecraft.world.phys.BlockHitResult)player.pick(player.blockInteractionRange(), 1, false);
      var fluidRay = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.clip(
         player, player.getEyePosition(), player.getViewVector(1), false);
      helper.assertTrue(blackOutline.getBlockPos().equals(floor), "vanilla black outline did not point through the water");
      helper.assertTrue(fluidRay.getBlockPos().equals(floor.above(2)), "setup did not distinguish FF's fluid ray from the black outline");
      var rules = io.github.fastformer.fastplace.geometry.raycast.InteractionRaycastRules.VANILLA_OUTLINE;
      IdleInteractionSnapshots.capture(player, new IdleInteractionCapturePayload(123, blackOutline,
         player.getEyePosition(), player.getViewVector(1), rules));
      var entry = IdleInteractionSnapshots.consumeEntry(player, 123, player.getEyePosition(), player.getViewVector(1));
      helper.assertTrue(entry != null && entry.hit().getBlockPos().equals(floor), "empty-hand double click selected water instead of the black outline");
      helper.assertTrue(entry.hit().getDirection() == blackOutline.getDirection()
         && entry.hit().getLocation().distanceTo(blackOutline.getLocation()) < 1.0E-6, "black-outline hit face or position changed");
      helper.assertTrue(entry.rules().equals(rules), "black-outline fluid rule was lost");
      helper.assertTrue(entry.placement() == null, "empty-hand selection created a placement context");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry", timeoutTicks = 600)
   public static void blackOutlineSnapshotKeepsThePlayersScaffoldingShape(GameTestHelper helper) {
      var player = player(helper);
      var pos = hit(player).getBlockPos().above();
      helper.getLevel().setBlock(pos, Blocks.SCAFFOLDING.defaultBlockState(), 2);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SCAFFOLDING));
      player.setPos(pos.getCenter().add(0, 0, -2));
      player.setXRot(0);
      player.setYRot(0);
      var eye = player.getEyePosition().subtract(0, player.getEyeHeight(), 0);
      var view = new net.minecraft.world.phys.Vec3(0, 0, 1);
      var rules = io.github.fastformer.fastplace.geometry.raycast.InteractionRaycastRules.VANILLA_OUTLINE;
      var snapshot = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.snapshot(player, pos, rules);
      var generic = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.snapshot(helper.getLevel(), pos, rules);
      var expected = Blocks.SCAFFOLDING.defaultBlockState().getShape(helper.getLevel(), pos,
         net.minecraft.world.phys.shapes.CollisionContext.of(player)).clip(eye, eye.add(view.scale(4)), pos);
      var captured = snapshot.clip(eye, view);
      helper.assertTrue(expected != null && captured != null && captured.getLocation().equals(expected.getLocation()),
         "snapshot ignored the player's held scaffolding outline");
      helper.assertTrue(!generic.boxes().equals(snapshot.boxes()), "setup did not distinguish generic and player-dependent outlines");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_embedded_submit", timeoutTicks = 6000)
   public static void embeddedMiddleEntrySubmitsOnlyTheFirstPoint(GameTestHelper helper) {
      var player = player(helper);
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      var hit = hit(player);
      boolean[] submitted = {false};
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         if (!submitted[0]) {
            helper.assertFalse(ServerInputDispatcher.interactionBlocked(player), "waiting for history");
            helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(
               player.getServer(), player.level().dimension()), "waiting for world writer");
            var target = new StartPlacementPayload.Target(PlayerPreviewSync.buildingRevision(player),
               PlayerPreviewSync.callbackScope(player), hit, player.getEyePosition(), player.getViewVector(1), 0, true, true);
            ServerInputDispatcher.startPlacement(player, new StartPlacementPayload(104, target));
            helper.assertFalse(FastPlaceManager.active(player), "middle entry left a line draft instead of submitting");
            submitted[0] = true;
         }
         FastPlaceManager.tickWorld(helper.getLevel().getServer());
         helper.assertFalse(FastPlaceManager.taskActive(player), "waiting for placement");
         helper.assertFalse(WorldHistoryManager.busy(player), "waiting for history publication");
         helper.assertTrue(helper.getLevel().getBlockState(hit.getBlockPos()).is(Blocks.OAK_LOG), "embedded entry did not replace the hit block");
         helper.assertTrue(helper.getLevel().getBlockState(hit.getBlockPos().above()).isAir(), "embedded entry placed an adjacent block");
      });
   }

   private static net.minecraft.world.phys.BlockHitResult hit(ServerPlayer player) {
      return LongRangeBlockRaycast.clipForPlacement(player.level(), player, player.getEyePosition(), player.getViewVector(1)).hit();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry")
   public static void distantEntryUsesCurrentRangeAndTheSameOutlineRay(GameTestHelper helper) {
      var player = player(helper);
      var stone = hit(player).getBlockPos();
      helper.getLevel().setBlock(stone.above(), Blocks.SHORT_GRASS.defaultBlockState(), 2);
      var attribute = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
      attribute.setBaseValue(20);
      var near = LongRangeBlockRaycast.clipForIdleEntry(player, false).hit();
      helper.assertTrue(near.getBlockPos().equals(stone.above()), "near entry did not preserve the original grass hit");
      helper.assertFalse(LongRangeBlockRaycast.beyondInteractionRange(player, near), "current extended vanilla range was ignored");
      attribute.setBaseValue(player.getEyePosition().distanceTo(near.getLocation()));
      helper.assertFalse(LongRangeBlockRaycast.beyondInteractionRange(player, near), "the range boundary was treated as distant");
      attribute.setBaseValue(1);
      var distant = LongRangeBlockRaycast.clipForIdleEntry(player, false).hit();
      helper.assertTrue(distant.getBlockPos().equals(stone.above()), "distant entry changed the outline ray criterion");
      helper.assertTrue(LongRangeBlockRaycast.beyondInteractionRange(player, distant), "updated vanilla range did not enable direct entry");
      helper.succeed();
   }

   private static StartPlacementPayload request(ServerPlayer player, long token, net.minecraft.world.phys.BlockHitResult hit) {
      return new StartPlacementPayload(token, new StartPlacementPayload.Target(PlayerPreviewSync.buildingRevision(player),
         PlayerPreviewSync.callbackScope(player), hit, player.getEyePosition(), player.getViewVector(1), token));
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry")
   public static void submergedSelectionRaysIgnoreWaterAndKeepSolidShapes(GameTestHelper helper) {
      var player = player(helper);
      var floor = hit(player).getBlockPos();
      var level = helper.getLevel();
      for (int height = 1; height <= 3; height++) level.setBlock(floor.above(height), Blocks.WATER.defaultBlockState(), 2);
      var ceiling = floor.above(5);
      level.setBlock(ceiling, Blocks.STONE.defaultBlockState(), 2);
      player.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(floor.above()));
      player.setXRot(-90);
      player.baseTick();
      helper.assertTrue(player.isInWater(), "test player did not enter water");
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var normal = LongRangeBlockRaycast.clipForSelection(level, player, eye, view, false).hit();
      var idle = LongRangeBlockRaycast.clipForIdleEntry(player, false).hit();
      helper.assertTrue(normal.getBlockPos().equals(ceiling), "submerged selection stopped at the water surface");
      helper.assertTrue(idle.getBlockPos().equals(ceiling), "submerged idle preview did not share the selection rule");
      var frozen = SessionEntryRaycast.clip(player, eye, view, InteractionRaycastRules.DEFAULT);
      helper.assertTrue(level.getBlockState(frozen.getBlockPos()).is(Blocks.WATER), "entering water changed explicitly frozen fluid rules");

      var slab = floor.above(4);
      level.setBlock(slab, Blocks.OAK_SLAB.defaultBlockState().setValue(
         net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true), 2);
      var solid = LongRangeBlockRaycast.clipForSelection(level, player, eye, view, false).hit();
      helper.assertTrue(solid.getBlockPos().equals(slab), "submerged ray also skipped the solid waterlogged slab");

      player.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(floor.above(4)));
      level.setBlock(slab, Blocks.AIR.defaultBlockState(), 2);
      level.setBlock(ceiling, Blocks.AIR.defaultBlockState(), 2);
      player.setXRot(90);
      player.baseTick();
      helper.assertFalse(player.isInWater(), "test player did not leave water");
      var outside = LongRangeBlockRaycast.clipForIdleEntry(player, false).hit();
      helper.assertTrue(outside.getBlockPos().equals(floor.above(3)), "leaving water did not restore normal fluid targeting");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "idle_entry")
   public static void entryRaysAndSnapshotsShareFluidShapesWhileAltPassesThrough(GameTestHelper helper) {
      var player = player(helper);
      var floor = hit(player).getBlockPos();
      var fluid = floor.above();
      var level = helper.getLevel();
      for (var state : java.util.List.of(Blocks.WATER.defaultBlockState(),
         Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 5),
         Blocks.LAVA.defaultBlockState(),
         Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true))) {
         level.setBlock(fluid, state, 2);
         var eye = player.getEyePosition();
         var view = player.getViewVector(1);
         var normal = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.clip(player, eye, view, false);
         var snapshot = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.snapshot(level, fluid).clip(eye, view);
         helper.assertTrue(normal.getBlockPos().equals(fluid), "normal entry skipped fluid: " + state);
         helper.assertTrue(snapshot != null && snapshot.getDirection() == normal.getDirection()
            && snapshot.getLocation().distanceTo(normal.getLocation()) < 1.0E-6,
            "snapshot and normal entry disagree: " + state);
         var alt = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.clip(player, eye, view, true);
         helper.assertTrue(alt.getBlockPos().equals(state.is(Blocks.OAK_SLAB) ? fluid : floor),
            "Alt did not ignore only the fluid shape: " + state);
         if (state.is(Blocks.OAK_SLAB)) {
            helper.assertTrue(alt.getLocation().y < normal.getLocation().y, "Alt ignored the solid slab too");
         }
         var transparentSnapshot = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.snapshot(level, fluid,
            io.github.fastformer.fastplace.geometry.raycast.InteractionRaycastRules.VANILLA_OUTLINE).clip(eye, view);
         helper.assertTrue(state.is(Blocks.OAK_SLAB)
            ? transparentSnapshot != null && transparentSnapshot.getLocation().distanceTo(alt.getLocation()) < 1.0E-6
            : transparentSnapshot == null, "through-fluid snapshot restored a fluid collision shape: " + state);
      }
      level.setBlock(fluid, Blocks.WATER.defaultBlockState(), 2);
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      var targetHit = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.clip(player, eye, view, true);
      var target = new StartPlacementPayload.Target(PlayerPreviewSync.buildingRevision(player),
         PlayerPreviewSync.callbackScope(player), targetHit, eye, view, 0, false, false, true);
      ServerInputDispatcher.startPlacement(player, new StartPlacementPayload(110, target));
      helper.assertTrue(FastPlaceManager.session(player).orElseThrow().points().equals(java.util.List.of(fluid)),
         "server rejected the Alt surface ray through water");
      FastPlaceManager.cancel(player);
      helper.succeed();
   }
}
