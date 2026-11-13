package io.github.fastformer.server.network.upload;

import io.github.fastformer.server.history.HistoryTestAccess;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceApplyPayload;
import io.github.fastformer.protocol.c2s.workspace.ShapePlacementPayload;
import io.github.fastformer.server.session.OperationManager;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UploadAdmissionGameTests {
   @GameTest(template = "fastformergametests.empty", batch = "network_upload_permissions", timeoutTicks = 600)
   public static void bothUploadKindsRejectSurvivalBeforeRetainingChunks(GameTestHelper helper) throws Exception {
      ServerPlayer player = io.github.fastformer.testkit.ServerPlayers.connect(helper, "upload-test");
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000);
      player.setGameMode(GameType.CREATIVE);
      BlockPos origin = helper.absolutePos(new BlockPos(1, 5, 1));
      OperationManager.startFirst(player, origin);
      OperationManager.startSecond(player, origin.offset(2, 0, 0));
      var draft = OperationManager.session(player).orElseThrow();
      player.setGameMode(GameType.SURVIVAL);
      UUID workspace = UUID.randomUUID(), shape = UUID.randomUUID();
      UploadSystem.of(player).receive(player, UploadKind.WORKSPACE, new OperationWorkspaceApplyPayload(workspace, 0, 2, new byte[] {1}));
      UploadSystem.of(player).receive(player, UploadKind.SHAPE, new ShapePlacementPayload(shape, 0, 2, new byte[] {1}));
      helper.assertFalse(UploadSystem.of(player).receipts().requestScope(player.getUUID(), workspace) != null, "workspace upload retained its scope");
      helper.assertFalse(UploadSystem.of(player).receipts().requestScope(player.getUUID(), shape) != null, "shape upload retained its scope");
      helper.assertFalse(OperationManager.taskActive(player), "denied upload started a task");
      helper.assertTrue(OperationManager.session(player).orElse(null) == draft, "denied upload cleared the draft");
      OperationManager.cancel(player);
      helper.succeed();
   }

}
