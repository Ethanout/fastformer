package io.github.fastformer.client.operation.workspace;

import io.github.fastformer.client.operation.clipboard.OperationClipboard;
import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.composition.Composition;
import io.github.fastformer.common.workspace.composition.CompositionBatch;
import io.github.fastformer.common.workspace.composition.CompositionBudget;
import io.github.fastformer.common.workspace.submission.OperationWorkspacePlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Builds non-empty clipboard and submission content without changing the workspace. */
public final class WorkspaceContentPreparer {
   private WorkspaceContentPreparer() {
   }

   public static ClipboardPreparation clipboardParts(List<ClientSelectionPart> parts) {
      List<OperationClipboard.Part> copied = new ArrayList<>();
      CompositionBatch batch = new CompositionBatch(CompositionBudget.INTERACTION);
      for (ClientSelectionPart part : safeParts(parts)) {
         Composition<BlockSnapshot> composition = batch.compose(part);
         if (composition instanceof Composition.OverBudget<BlockSnapshot> over) {
            return new ClipboardPreparation.TooLarge(over.limit(), over.cap(), over.reached());
         }
         Map<BlockPos, BlockSnapshot> blocks = ((Composition.Composed<BlockSnapshot>)composition).values();
         if (!blocks.isEmpty()) {
            copied.add(new OperationClipboard.Part(part.id(), blocks));
         }
      }
      return copied.isEmpty() ? new ClipboardPreparation.Empty() : new ClipboardPreparation.Copied(copied);
   }

   public static List<OperationWorkspacePlan.Part> submissionParts(List<ClientSelectionPart> parts) {
      return safeParts(parts).stream()
         .filter(part -> !part.isOriginalSelection())
         .map(WorkspaceContentPreparer::submissionPart)
         .filter(part -> !part.blocks().isEmpty())
         .toList();
   }

   /** Explicit confirmation includes adjustable parts without changing their undo state. */
   public static List<OperationWorkspacePlan.Part> confirmedParts(List<ClientSelectionPart> parts) {
      return submissionParts(safeParts(parts).stream().map(ClientSelectionPart::fixed).toList());
   }

   private static List<ClientSelectionPart> safeParts(List<ClientSelectionPart> parts) {
      return parts == null ? List.of() : parts.stream().filter(java.util.Objects::nonNull).toList();
   }

   private static OperationWorkspacePlan.Part submissionPart(ClientSelectionPart part) {
      Map<BlockPos, BlockSnapshot> blocks = part.pendingDelete() && !part.sourceSnapshot().isEmpty()
         ? part.sourceSnapshot()
         : part.blocks();
      return new OperationWorkspacePlan.Part(
         part.id(), part.source(), blocks, part.transform(), part.pendingDelete()
      );
   }
}
