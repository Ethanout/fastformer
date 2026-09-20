package io.github.fastformer.client.input;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** A point command is applied to the current draft stage in mailbox order. */
record SelectionDraftPress(UUID owner, OperationSelectionMode mode, int button, BlockPos point,
   boolean alt, boolean control, long occurredAtNanos) {
   SelectionDraftPress {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(mode, "mode");
      if (button < 0 || button > 2 || point == null && !alt) {
         throw new IllegalArgumentException("A draft press requires a supported button and a target");
      }
      point = point == null ? null : point.immutable();
   }

   boolean matches(UUID currentOwner, OperationSelectionMode currentMode) {
      return this.owner.equals(currentOwner) && this.mode == currentMode;
   }
}
