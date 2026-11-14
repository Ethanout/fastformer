package io.github.fastformer.server.session;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.history.WorkspaceReplayGameTests;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;
import io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.common.workspace.submission.OperationWorkspacePlan;
import io.github.fastformer.common.workspace.submission.WorkspaceAdmission;
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
               var operations = OperationSystem.of(player);
               var feedback = operations.admissionFeedback;
               operations.admissionFeedback = ignored -> {
                  throw new IllegalStateException("expected feedback fault");
               };
               WorkspaceAdmission admission;
               try {
                  admission = OperationSystem.of(player).applyWorkspace(player, transferId, plan(helper, 1, 1));
               } finally {
                  operations.admissionFeedback = feedback;
               }

               // The fault happened after the queue accepted the task, so the admission stands.
               helper.assertTrue(admission.isQueued(), "a feedback fault changed the admission");
               helper.assertTrue(
                  SessionSystem.of(server).submissions().outcomeFor(player.getUUID(), dimension, transferId)
                     == OperationSubmissionOutcome.IN_PROGRESS,
                  "a feedback fault changed the recorded state"
               );
               helper.assertTrue(
                  SessionSystem.of(player).operationTasks().transferActive(player.getUUID(), transferId),
                  "a feedback fault lost the queued task"
               );
               SessionSystem.of(player).operationTasks().cancel(player);
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
         SessionSystem.of(server).submissions().clearOwner(player.getUUID());
      });
   }

   private static OperationWorkspacePlan plan(GameTestHelper helper, int x, int z) {
      BlockPos pos = helper.absolutePos(new BlockPos(x, 1, z));
      Map<BlockPos, BlockSnapshot> blocks = new LinkedHashMap<>();
      blocks.put(pos, new BlockSnapshot(Blocks.STONE.defaultBlockState(), null));
      return new OperationWorkspacePlan(List.of(new OperationWorkspacePlan.Part(
         1, ClientSelectionPart.Source.WORLD, blocks, WorkspaceTransform.IDENTITY, false
      )));
   }
}
