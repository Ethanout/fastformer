package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.world.WorkspaceReplayGameTests;
import io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A fault in the feedback of an accepted admission.
 *
 * <p>The admission is complete once the task is in the queue: the world tick loop walks that
 * queue, so the task runs even when a later step fails. Feedback and the first resume are
 * therefore reports of a finished admission, and a fault in either one must not travel out
 * of {@code applyWorkspace}. A caller that caught such a fault would report a failure for
 * work that runs.</p>
 *
 * <p>This test injects the fault instead of hoping for one, so the boundary is covered
 * rather than inferring it.</p>
 */
@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkspaceAdmissionFaultGameTests {
   private WorkspaceAdmissionFaultGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "workspace_fault", timeoutTicks = 600)
   public static void aFeedbackFaultDoesNotTurnAQueuedAdmissionIntoAFailure(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      UUID transferId = UUID.randomUUID();
      var server = helper.getLevel().getServer();
      var dimension = helper.getLevel().dimension().location();
      boolean[] admitted = {false};
      RuntimeException[] admissionFailure = {null};
      helper.succeedWhen(() -> {
         if (admissionFailure[0] != null) {
            throw admissionFailure[0];
         }
         if (!admitted[0]) {
            helper.assertTrue(
               !WorkspaceReplayGameTests.admissionBlocked(player),
               "waiting for the write lease of a neighbouring batch"
            );
            admitted[0] = true;
            try {
               OperationManager.setAdmissionFeedbackForTest(ignored -> {
                  throw new IllegalStateException("expected feedback fault");
               });
               WorkspaceAdmission admission;
               try {
                  admission = OperationManager.applyWorkspace(player, transferId, plan(helper, 1, 1));
               } finally {
                  OperationManager.setAdmissionFeedbackForTest(null);
               }

               // The fault happened after the queue accepted the task, so the admission stands.
               helper.assertTrue(admission.isQueued(), "a feedback fault changed the admission");
               helper.assertTrue(
                  WorkspaceSubmissionLedger.outcomeFor(server, player.getUUID(), dimension, transferId)
                     == OperationSubmissionOutcome.IN_PROGRESS,
                  "a feedback fault changed the recorded state"
               );
               helper.assertTrue(
                  OperationManager.transferActive(player.getUUID(), transferId),
                  "a feedback fault lost the queued task"
               );
               OperationManager.cancelTask(player);
            } catch (RuntimeException exception) {
               admissionFailure[0] = exception;
               throw exception;
            }
         }
         // The lease of the queued task is released asynchronously.
         helper.assertTrue(
            !WorkspaceReplayGameTests.admissionBlocked(player), "waiting for the released write lease"
         );
         // Only this test's own record leaves. The maps of other owners stay untouched.
         WorkspaceSubmissionLedger.clearOwner(server, player.getUUID());
      });
   }

   private static OperationWorkspacePlan plan(GameTestHelper helper, int x, int z) {
      BlockPos pos = helper.absolutePos(new BlockPos(x, 1, z));
      Map<BlockPos, ClientBlockSnapshot> blocks = new LinkedHashMap<>();
      blocks.put(pos, new ClientBlockSnapshot(Blocks.STONE.defaultBlockState(), null));
      return new OperationWorkspacePlan(List.of(new OperationWorkspacePlan.Part(
         1, ClientSelectionPart.Source.WORLD, blocks, WorkspaceTransform.IDENTITY, false
      )));
   }
}
