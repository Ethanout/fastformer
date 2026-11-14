package io.github.fastformer.client.operation.pointer;

import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import net.minecraft.world.phys.AABB;

/** Defines which workspace interactions remain available after a part is transformed. */
public final class WorkspacePartInteractionCapabilities {
   private WorkspacePartInteractionCapabilities() {
   }

   public static boolean canSelect(ClientSelectionPart part, AABB bounds) {
      return part != null && bounds != null;
   }

   public static boolean canEditSource(ClientSelectionPart part) {
      return part != null && part.editability() == ClientSelectionPart.Editability.FREE;
   }
}
