package io.github.fastformer.client.operation.input;

import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.client.operation.selection.tool.SelectionTools;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** A point command is applied to the current draft stage in mailbox order. */
public record SelectionDraftPress(UUID owner, OperationSelectionMode mode, int button, BlockPos point,
   boolean alt, boolean control, long occurredAtNanos, ResourceLocation toolId) {
   public SelectionDraftPress(UUID owner, OperationSelectionMode mode, int button, BlockPos point,
      boolean alt, boolean control, long occurredAtNanos) {
      this(owner, mode, button, point, alt, control, occurredAtNanos,
         SelectionTools.builtinId(mode));
   }
   public SelectionDraftPress {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(toolId, "toolId");
      boolean pointUndo = button == 0 && SelectionTools.require(toolId).undoPreviousPoint();
      if (button < 0 || button > 2 || point == null && !alt && !pointUndo) {
         throw new IllegalArgumentException("A draft press requires a supported button and a target");
      }
      point = point == null ? null : point.immutable();
   }

   public boolean matches(UUID currentOwner, OperationSelectionMode currentMode) {
      return this.owner.equals(currentOwner) && this.mode == currentMode;
   }
   public boolean matches(UUID currentOwner, ResourceLocation currentTool) {
      return owner.equals(currentOwner) && toolId.equals(currentTool);
   }
}
