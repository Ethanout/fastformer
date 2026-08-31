package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Snapshot retained only while a transformed part is locked. */
public record SelectionBaseline(OperationSelectionVolume selection, WorkspaceTransform transform, Map<BlockPos, ClientBlockSnapshot> sourceSnapshot) {
   public boolean matches(OperationSelectionVolume currentSelection, WorkspaceTransform currentTransform, Map<BlockPos, ClientBlockSnapshot> currentSource) {
      return java.util.Objects.equals(this.selection, currentSelection) && this.transform.equals(currentTransform) && this.sourceSnapshot.equals(currentSource);
   }
}
