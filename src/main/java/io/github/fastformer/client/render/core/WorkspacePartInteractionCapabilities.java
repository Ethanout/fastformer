package io.github.fastformer.client.render.core;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import net.minecraft.world.phys.AABB;

/** Defines which workspace interactions remain available after a part is transformed. */
final class WorkspacePartInteractionCapabilities {
   private WorkspacePartInteractionCapabilities() {
   }

   static boolean canSelect(ClientSelectionPart part, AABB bounds) {
      return part != null && bounds != null;
   }

   static boolean canEditSource(ClientSelectionPart part) {
      return part != null && part.editability() == ClientSelectionPart.Editability.FREE;
   }
}
