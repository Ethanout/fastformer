package io.github.fastformer.common.workspace.preview;

import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.transform.VoxelRotation;
import java.util.Objects;

/** Shares work and retained output limits across one multi-part action. */
public final class CompositionBatch {
   private final CompositionCounter counter;
   private Composition.OverBudget<BlockSnapshot> failure;

   public CompositionBatch(CompositionBudget budget) {
      this.counter = new CompositionCounter(Objects.requireNonNull(budget, "budget"));
   }

   public Composition<BlockSnapshot> compose(ClientSelectionPart part) {
      if (this.failure != null) {
         return this.failure;
      }
      Composition<BlockSnapshot> result = WorkspacePreviewComposer.compose(
         part.blocks(), part.transform(), VoxelRotation.snapshotValues(), this.counter
      );
      if (result instanceof Composition.Composed<BlockSnapshot> composed) {
         this.counter.retainOutput(composed.values().size());
      } else if (result instanceof Composition.OverBudget<BlockSnapshot> over) {
         this.failure = over;
      }
      return result;
   }
}
