package io.github.fastformer.client.operation.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.fastformer.client.operation.clipboard.ClipboardCopy;
import io.github.fastformer.client.operation.clipboard.OperationClipboard;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.preview.Composition;
import io.github.fastformer.client.operation.preview.CompositionBatch;
import io.github.fastformer.client.operation.preview.CompositionBudget;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class WorkspaceContentPreparerTest {
   @Test
   void overlappingPartsStillConsumeSeparateRetainedOutputBudgets() {
      var blocks = Map.of(BlockPos.ZERO, snapshot(), new BlockPos(1, 0, 0), snapshot());
      var batch = new CompositionBatch(new CompositionBudget(3, 100));
      assertInstanceOf(Composition.Composed.class, batch.compose(part(1, blocks, false, Map.of())));
      var refusal = assertInstanceOf(Composition.OverBudget.class,
         batch.compose(part(2, blocks, false, Map.of())));
      assertEquals(Composition.Limit.OUTPUT, refusal.limit());
      assertEquals(3L, refusal.cap());
      assertEquals(4L, refusal.reached());
   }

   @Test
   void workBudgetIsSharedEvenWhenEachPartFitsIndividually() {
      var blocks = Map.of(BlockPos.ZERO, snapshot(), new BlockPos(1, 0, 0), snapshot());
      var batch = new CompositionBatch(new CompositionBudget(10, 3));
      assertInstanceOf(Composition.Composed.class, batch.compose(part(1, blocks, false, Map.of())));
      var refusal = assertInstanceOf(Composition.OverBudget.class,
         batch.compose(part(2, blocks, false, Map.of())));
      assertEquals(Composition.Limit.WORK, refusal.limit());
      assertEquals(4L, refusal.reached());
      assertSame(refusal, batch.compose(part(3, Map.of(), false, Map.of())));
   }

   @Test
   void exactBatchLimitStillAllowsAnEmptyPart() {
      var batch = new CompositionBatch(new CompositionBudget(1, 1));
      assertInstanceOf(Composition.Composed.class,
         batch.compose(part(1, Map.of(BlockPos.ZERO, snapshot()), false, Map.of())));
      var empty = assertInstanceOf(Composition.Composed.class,
         batch.compose(part(2, Map.of(), false, Map.of())));
      assertTrue(empty.values().isEmpty());
   }

   @Test
   void clipboardRefusesTheCombinedOutputOfIndividuallyLegalParts() {
      WorkspaceTransform repeat = WorkspaceTransform.IDENTITY.withRepeats(
         new OperationStackRegion(BlockPos.ZERO, new BlockPos(99, 99, 10)), new BlockPos(1, 1, 1)
      );
      var blocks = Map.of(BlockPos.ZERO, snapshot());
      var first = part(1, blocks, false, Map.of(), repeat);
      var second = part(2, blocks, false, Map.of(), repeat);
      assertInstanceOf(ClipboardPreparation.Copied.class,
         WorkspaceContentPreparer.clipboardParts(List.of(first)));
      var refusal = assertInstanceOf(ClipboardPreparation.TooLarge.class,
         WorkspaceContentPreparer.clipboardParts(List.of(first, second)));
      assertEquals(Composition.Limit.OUTPUT, refusal.limit());
      assertEquals(CompositionBudget.INTERACTION.maxOutputCells(), refusal.cap());
   }

   @Test
   void clipboardPreparationSkipsEmptyPartsBeforeConstructingClipboardParts() {
      ClientSelectionPart empty = part(1, Map.of(), false, Map.of());
      ClientSelectionPart content = part(2, Map.of(BlockPos.ZERO, snapshot()), false, Map.of());

      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      assertTrue(workspace.addParts(List.of(empty, content)));
      var copy = OperationClipboard.fromWorkspace(workspace);
      assertTrue(copy instanceof ClipboardCopy.Copied);
      var copied = ((ClipboardCopy.Copied)copy).clipboard().parts();

      assertEquals(1, copied.size());
      assertEquals(2, copied.getFirst().originalId());
      assertEquals(1, copied.getFirst().blocks().size());
   }

   @Test
   void submissionPreparationDropsAnAllEmptyWorkspace() {
      ClientSelectionPart empty = part(
         1, Map.of(), false, Map.of(), WorkspaceTransform.IDENTITY.withTranslation(new Vec3(2, 3, 4))
      );
      ClientOperationWorkspace workspace = new ClientOperationWorkspace();
      assertTrue(workspace.addParts(List.of(empty)));
      ClientOperationWorkspace.DraftState before = workspace.draftState();
      var selectionBefore = workspace.selectedIds();
      WorkspaceTransform transformBefore = workspace.part(1).orElseThrow().transform();

      assertTrue(OperationClipboard.fromWorkspace(workspace) instanceof ClipboardCopy.Empty);
      assertTrue(WorkspaceContentPreparer.submissionParts(workspace.parts()).isEmpty());
      assertEquals(before, workspace.draftState());
      assertEquals(selectionBefore, workspace.selectedIds());
      assertEquals(transformBefore, workspace.part(1).orElseThrow().transform());
   }

   @Test
   void submissionPreparationKeepsContentAndPendingDeleteSourceSnapshots() {
      ClientBlockSnapshot content = snapshot();
      ClientSelectionPart empty = part(1, Map.of(), false, Map.of());
      ClientSelectionPart write = part(2, Map.of(BlockPos.ZERO, content), false, Map.of())
         .withTranslation(new Vec3(1, 0, 0));
      BlockPos source = new BlockPos(4, 5, 6);
      ClientSelectionPart delete = part(3, Map.of(), true, Map.of(source, content));

      var parts = WorkspaceContentPreparer.submissionParts(List.of(empty, write, delete));

      assertEquals(2, parts.size());
      assertEquals(2, parts.get(0).id());
      assertEquals(3, parts.get(1).id());
      assertTrue(parts.get(1).pendingDelete());
      assertEquals(1, parts.get(1).blocks().size());
      assertTrue(parts.get(1).blocks().containsKey(source));
      assertSame(content, parts.get(1).blocks().get(source));
   }

   @Test
   void unchangedAndRestoredSelectionsAreNotWorldWritesButRemainCopyable() {
      var blocks = Map.of(BlockPos.ZERO, snapshot());
      var selection = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, OperationSelectionVolume.cuboid(
         BlockPos.ZERO, BlockPos.ZERO, null, null),
         blocks, WorkspaceTransform.IDENTITY, false);
      assertTrue(selection.isOriginalSelection());
      var moved = selection.withTranslation(new Vec3(2, 0, 0));
      assertTrue(!moved.isOriginalSelection());
      var restored = moved.withTranslation(Vec3.ZERO);
      assertTrue(WorkspaceContentPreparer.submissionParts(List.of(selection, restored)).isEmpty());
      assertEquals(1, WorkspaceContentPreparer.submissionParts(List.of(moved)).size());
      assertInstanceOf(ClipboardPreparation.Copied.class,
         WorkspaceContentPreparer.clipboardParts(List.of(restored)));
   }

   @Test
   void clipboardContentsStillSubmitWithoutATransformAlongsideUnchangedSelection() {
      var blocks = Map.of(BlockPos.ZERO, snapshot());
      var selection = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, OperationSelectionVolume.cuboid(
         BlockPos.ZERO, BlockPos.ZERO, null, null),
         blocks, WorkspaceTransform.IDENTITY, false);
      var pasted = new ClientSelectionPart(2, ClientSelectionPart.Source.CLIPBOARD, OperationSelectionVolume.cuboid(
         BlockPos.ZERO, BlockPos.ZERO, null, null),
         blocks, WorkspaceTransform.IDENTITY, false);
      var submitted = WorkspaceContentPreparer.submissionParts(List.of(selection, pasted));
      assertEquals(1, submitted.size());
      assertEquals(ClientSelectionPart.Source.CLIPBOARD, submitted.getFirst().source());
      assertEquals(2, submitted.getFirst().id());
   }

   private static ClientSelectionPart part(
      int id, Map<BlockPos, ClientBlockSnapshot> blocks, boolean pendingDelete,
      Map<BlockPos, ClientBlockSnapshot> sourceSnapshot
   ) {
      return part(id, blocks, pendingDelete, sourceSnapshot, WorkspaceTransform.IDENTITY);
   }

   private static ClientSelectionPart part(
      int id, Map<BlockPos, ClientBlockSnapshot> blocks, boolean pendingDelete,
      Map<BlockPos, ClientBlockSnapshot> sourceSnapshot, WorkspaceTransform transform
   ) {
      return new ClientSelectionPart(
         id,
         ClientSelectionPart.Source.WORLD,
         null,
         blocks,
         transform,
         pendingDelete,
         sourceSnapshot,
         null,
         ClientSelectionPart.Editability.FREE
      );
   }

   private static ClientBlockSnapshot snapshot() {
      try {
         Field field = Unsafe.class.getDeclaredField("theUnsafe");
         field.setAccessible(true);
         return (ClientBlockSnapshot)((Unsafe)field.get(null)).allocateInstance(ClientBlockSnapshot.class);
      } catch (ReflectiveOperationException exception) {
         throw new AssertionError(exception);
      }
   }
}
