package io.github.fastformer.client.operation.preview;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.transform.VoxelRotation;
import java.util.Objects;

/** Shares work and retained output limits across one multi-part action. */
public final class CompositionBatch {
   private final CompositionCounter counter;
   private Composition.OverBudget<ClientBlockSnapshot> failure;

   public CompositionBatch(CompositionBudget budget) {
      this.counter = new CompositionCounter(Objects.requireNonNull(budget, "budget"));
   }

   public Composition<ClientBlockSnapshot> compose(ClientSelectionPart part) {
      if (this.failure != null) {
         return this.failure;
      }
      Composition<ClientBlockSnapshot> result = WorkspacePreviewComposer.compose(
         part.blocks(), part.transform(), VoxelRotation.snapshotValues(), this.counter
      );
      if (result instanceof Composition.Composed<ClientBlockSnapshot> composed) {
         this.counter.retainOutput(composed.values().size());
      } else if (result instanceof Composition.OverBudget<ClientBlockSnapshot> over) {
         this.failure = over;
      }
      return result;
   }
}
