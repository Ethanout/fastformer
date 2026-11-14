package io.github.fastformer.server.interaction;

import io.github.fastformer.server.history.HistoryTestAccess;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.history.HistoryService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IdleSessionGameTests {
   private IdleSessionGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "idle_wrench", timeoutTicks = 6000)
   public static void wrenchStillWorksThroughTheBlockEventDuringIdleHandoff(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000L);
      var pos = helper.absolutePos(new BlockPos(1, 3, 1));
      helper.setBlock(new BlockPos(1, 3, 1), Blocks.GRASS_BLOCK);
      player.setPos(pos.getCenter().add(0, 2, 0));
      player.setXRot(90);
      var hit = new BlockHitResult(pos.getCenter().add(0, 0.5, 0), Direction.UP, pos, false);
      boolean[] applied = {false};
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         if (!applied[0]) {
            helper.assertFalse(io.github.fastformer.server.input.ServerInputActions.interactionBlocked(player), "waiting for history");
            helper.assertFalse(io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeLeases().busy(player.level().dimension()), "waiting for world write");
            var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
            NeoForge.EVENT_BUS.post(event);
            helper.assertTrue(event.isCanceled(), "wrench was skipped by the idle session");
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.DIRT_PATH), "wrench did not convert grass to path");
            applied[0] = true;
         }
         helper.assertTrue(io.github.fastformer.server.input.IdleInteractionSnapshots.idle(player), "wrench started a session");
         helper.assertFalse(io.github.fastformer.server.session.OperationSystem.of(player).active(player), "wrench opened a selection");
      });
   }
}
