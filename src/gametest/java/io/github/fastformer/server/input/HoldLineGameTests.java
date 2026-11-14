package io.github.fastformer.server.input;

import io.github.fastformer.common.raycast.LongRangeBlockRaycast;

import io.github.fastformer.common.quickshape.mode.LineMode;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.common.raycast.InteractionRaycastRules;

import io.github.fastformer.server.history.HistoryTestAccess;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.common.raycast.*;
import io.github.fastformer.server.settings.FastPlaceSettings;
import io.github.fastformer.protocol.c2s.placement.StartPlacementPayload;
import io.github.fastformer.protocol.c2s.placement.QuickShapePointerPayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.session.QuickShapeSystem;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.gametest.*;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HoldLineGameTests {
   private static final ResourceLocation PROFILE = ResourceLocation.parse("fastformer:hold_line");
   private HoldLineGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "hold_line", timeoutTicks = 600)
   public static void profileLocksModeWithoutChangingPlayerSettings(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      helper.succeedWhen(() -> {
         ready(helper, player);
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         settings.setMode(player, LineMode.FREE_SCROLL);
         settings.setMiddleConfirmEnabled(player, false);
         PreviewSync.syncSettings(player);
         BlockHitResult hit = hit(player);
         ServerInputActions.startPlacement(player, request(player, 1, hit, PROFILE));
         var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
         helper.assertTrue(draft.profileId().equals(PROFILE), "server did not resolve the synchronized profile");
         helper.assertTrue(draft.points().equals(List.of(hit.getBlockPos())), "hold start was not embedded in the frozen hit");
         helper.assertTrue(QuickShapeSystem.effectiveModes(settings, draft).lineMode() == LineMode.AXIS, "profile did not lock the effective line mode");
         QuickShapeSystem.of(player).cycleStageMode(player);
         QuickShapeSystem.of(player).setStageMode(player, LineMode.RAYCAST);
         QuickShapeSystem.of(player).scrollContext(player, 3);
         helper.assertTrue(settings.storedLineMode() == LineMode.FREE_SCROLL, "profile changed the saved player mode");
         helper.assertTrue(QuickShapeSystem.effectiveModes(settings, draft).lineMode() == LineMode.AXIS, "mode action bypassed the profile lock");
         helper.assertFalse(QuickShapeSystem.of(player).closePolygon(player), "hold line closed a polygon");
         SessionSystem.of(player).cancelEditing(player);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "hold_line", timeoutTicks = 6000)
   public static void middleSubmitsEmbeddedLineEvenWhenMiddleSettingIsOff(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      BlockPos[] first = {null};
      boolean[] submitted = {false};
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         if (!submitted[0]) {
            ready(helper, player);
            var settings = FastPlaceSettings.load(player);
            settings.setMiddleConfirmEnabled(player, false);
            settings.setMode(player, LineMode.RAYCAST);
            PreviewSync.syncSettings(player);
            var start = hit(player);
            ServerInputActions.startPlacement(player, request(player, 11, start, PROFILE));
            var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
            first[0] = draft.points().getFirst();
            player.setPos(player.position().add(3, 0, 0));
            var eye = player.getEyePosition();
            var view = player.getViewVector(1);
            var candidate = QuickShapeSystem.of(player).candidateContext(player).resolve(
               LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view).hit(), eye, view);
            helper.assertTrue(candidate.distManhattan(first[0]) > 0, "fixture did not produce a line");
            ServerInputActions.quickShapePointer(player, new QuickShapePointerPayload(
               PreviewSync.buildingRevision(player), PreviewSync.callbackScope(player),
               QuickShapePointerPayload.Action.MIDDLE, candidate, eye, view, false, 12));
            helper.assertFalse(QuickShapeSystem.of(player).active(player), "middle setting prevented hold release submission");
            helper.assertTrue(settings.storedLineMode() == LineMode.RAYCAST, "release overwrote the saved line mode");
            submitted[0] = true;
         }
         SessionSystem.of(helper.getLevel().getServer()).placementTasks().tickWorld(helper.getLevel().getServer(), io.github.fastformer.server.history.HistorySystem.of(helper.getLevel().getServer()));
         helper.assertFalse(SessionSystem.of(player).placementTasks().active(player), "waiting for line placement");
         helper.assertTrue(helper.getLevel().getBlockState(first[0]).is(Blocks.GOLD_BLOCK), "embedded starting block was not included");
         helper.assertTrue(helper.getLevel().getBlockState(first[0].above()).isAir(), "surface entry left an extra block above the start");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "hold_line", timeoutTicks = 600)
   public static void unknownProfileAndOutOfReachProfileEntryAreRejected(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      helper.succeedWhen(() -> {
         ready(helper, player);
         ServerInputActions.startPlacement(player, request(player, 21, hit(player), ResourceLocation.parse("fastformer:missing")));
         helper.assertFalse(QuickShapeSystem.of(player).active(player), "unknown profile created a draft");
         player.setPos(player.position().add(0, 20, 0));
         PreviewSync.syncSettings(player);
         ServerInputActions.startPlacement(player, request(player, 22, hit(player), PROFILE));
         helper.assertFalse(QuickShapeSystem.of(player).active(player), "hold line started beyond player interaction range");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "hold_line", timeoutTicks = 600)
   public static void frozenOutlineThroughWaterUsesTheSameEmbeddedStart(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      helper.succeedWhen(() -> {
         ready(helper, player);
         BlockPos target = hit(player).getBlockPos();
         helper.getLevel().setBlock(target.above(), Blocks.WATER.defaultBlockState(), 2);
         var frozen = hit(player);
         helper.assertTrue(frozen.getBlockPos().equals(target), "frozen outline did not ignore fluid");
         ServerInputActions.startPlacement(player, request(player, 31, frozen, PROFILE));
         var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
         helper.assertTrue(draft.points().equals(List.of(target)), "hold anchor was recast against the water surface");
         SessionSystem.of(player).cancelEditing(player);
      });
   }

   private static ServerPlayer player(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      BlockPos target = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 2);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_BLOCK));
      player.setPos(target.getCenter().add(0, 2, 0));
      player.setXRot(90);
      PreviewSync.syncSettings(player);
      return player;
   }

   private static BlockHitResult hit(ServerPlayer player) {
      return SessionEntryRaycast.clip(player, player.getEyePosition(), player.getViewVector(1), InteractionRaycastRules.VANILLA_OUTLINE);
   }

   private static StartPlacementPayload request(ServerPlayer player, long id, BlockHitResult hit, ResourceLocation profile) {
      return new StartPlacementPayload(id, new StartPlacementPayload.Target(PreviewSync.buildingRevision(player),
         PreviewSync.callbackScope(player), hit, player.getEyePosition(), player.getViewVector(1), 0, true, false, true, profile));
   }

   private static void ready(GameTestHelper helper, ServerPlayer player) {
      helper.assertTrue(ServerInputActions.canOperate(player), "waiting for world gate");
      helper.assertFalse(ServerInputActions.interactionBlocked(player), "waiting for initial history");
      helper.assertFalse(io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeLeases().busy(player.level().dimension()), "waiting for world writer");
   }
}
