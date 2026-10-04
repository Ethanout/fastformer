package io.github.fastformer.fastplace.interaction;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.session.IdleSessionState;
import io.github.fastformer.network.payload.settings.IdleSessionPayload;
import io.github.fastformer.server.session.ServerIdleSession;
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
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      var pos = helper.absolutePos(new BlockPos(1, 3, 1));
      helper.setBlock(new BlockPos(1, 3, 1), Blocks.GRASS_BLOCK);
      player.setPos(pos.getCenter().add(0, 2, 0));
      player.setXRot(90);
      ServerIdleSession.receive(player, new IdleSessionPayload(IdleSessionState.WAITING_FOR_FAR_EXIT, player.level().dimension().location()));
      var hit = new BlockHitResult(pos.getCenter().add(0, 0.5, 0), Direction.UP, pos, false);
      boolean[] applied = {false};
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         if (!applied[0]) {
            helper.assertFalse(io.github.fastformer.server.input.ServerInputDispatcher.interactionBlocked(player), "waiting for history");
            helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(
               player.getServer(), player.level().dimension()), "waiting for world write");
            var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
            NeoForge.EVENT_BUS.post(event);
            helper.assertTrue(event.isCanceled(), "wrench was skipped by the idle session");
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.DIRT_PATH), "wrench did not convert grass to path");
            applied[0] = true;
         }
         helper.assertTrue(ServerIdleSession.state(player).suspended(), "wrench resumed the session");
         helper.assertFalse(io.github.fastformer.server.session.OperationManager.active(player), "wrench opened a selection");
      });
   }
}
