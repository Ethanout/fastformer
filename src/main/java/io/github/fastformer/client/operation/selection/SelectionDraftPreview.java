package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.operation.selection.tool.SelectionTools;

import io.github.fastformer.common.selection.OperationSelectionVolume;
import net.minecraft.core.BlockPos;

/** Confirmation and deletion use the same volume as the unfinished selection. */
public final class SelectionDraftPreview {
   private SelectionDraftPreview() { }
   public static OperationSelectionVolume volume(ClientSelectionSession.DraftState draft, BlockPos candidate) {
      return SelectionTools.require(draft.toolId()).volume(draft.toolState(), candidate);
   }
}
