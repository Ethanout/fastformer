package io.github.fastformer.client.interaction;

import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.composition.Composition;
import io.github.fastformer.common.workspace.composition.CompositionBudget;
import io.github.fastformer.common.workspace.composition.WorkspaceComposer;
import io.github.fastformer.common.workspace.composition.WorkspaceSelectionBounds;
import io.github.fastformer.common.workspace.selection.OccupiedBlockBounds;
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
      var composition = WorkspaceComposer.composeSnapshots(
         part.blocks(), part.transform().withoutRepeats(), CompositionBudget.RENDER
      );
      if (composition instanceof Composition.Composed<BlockSnapshot> composed) {
         return OccupiedBlockBounds.from(composed.values().keySet()).map(OccupiedBlockBounds::aabb).orElse(null);
      }
      return null;
   }
}
