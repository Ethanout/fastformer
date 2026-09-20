package io.github.fastformer.client.interaction;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.preview.Composition;
import io.github.fastformer.client.operation.preview.CompositionBudget;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.client.operation.preview.WorkspaceSelectionBounds;
import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import net.minecraft.world.phys.AABB;

/** World-space geometry shared by the visible frame and its selection targets. */
public final class PartInteractionBounds {
   private PartInteractionBounds() { }

   public static AABB resolve(ClientSelectionPart part) {
      if (part == null) {
         return null;
      }
      if (part.selection() != null) {
         return part.transform().hasEffect()
            ? WorkspaceSelectionBounds.resolve(part)
            : part.selection().bounds();
      }
      // Clipboard frames retain the existing base-part envelope, excluding copies.
      var composition = WorkspacePreviewComposer.composeSnapshots(
         part.blocks(), part.transform().withoutRepeats(), CompositionBudget.RENDER
      );
      if (composition instanceof Composition.Composed<ClientBlockSnapshot> composed) {
         return OccupiedBlockBounds.from(composed.values().keySet()).map(OccupiedBlockBounds::aabb).orElse(null);
      }
      return null;
   }
}
