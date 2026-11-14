package io.github.fastformer.client.operation.clipboard;

import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.workspace.ClipboardPreparation;
import io.github.fastformer.client.operation.workspace.WorkspaceContentPreparer;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;
import io.github.fastformer.common.workspace.selection.OccupiedBlockBounds;
import io.github.fastformer.common.world.BlockPositionMaps;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Persistent resolved contents copied from the current client preview. */
public record OperationClipboard(List<Part> parts) {
   public static final int VERSION = 1;

   public OperationClipboard {
      parts = parts == null ? List.of() : List.copyOf(parts);
      if (parts.isEmpty() || parts.size() > ClientOperationWorkspace.MAX_PARTS) {
         throw new IllegalArgumentException("Clipboard must contain at least one part");
      }
   }

   public static ClipboardCopy fromWorkspace(ClientOperationWorkspace workspace) {
      ClipboardPreparation preparation = WorkspaceContentPreparer.clipboardParts(workspace.selectedParts());
      if (preparation instanceof ClipboardPreparation.Copied copied) {
         return new ClipboardCopy.Copied(new OperationClipboard(copied.parts()));
      }
      if (preparation instanceof ClipboardPreparation.TooLarge tooLarge) {
         return new ClipboardCopy.TooLarge(tooLarge.limit(), tooLarge.cap(), tooLarge.reached());
      }
      return new ClipboardCopy.Empty();
   }

   public OccupiedBlockBounds bounds() {
      return this.parts.stream()
         .map(part -> OccupiedBlockBounds.from(part.blocks.keySet()).orElseThrow())
         .reduce(OccupiedBlockBounds::union)
         .orElseThrow();
   }

   public List<ClientSelectionPart> instantiate() {
      return this.parts.stream().map(part -> {
         OccupiedBlockBounds bounds = OccupiedBlockBounds.from(part.blocks.keySet()).orElseThrow();
         var selection = io.github.fastformer.common.selection.OperationSelectionVolume.create(
            io.github.fastformer.common.selection.OperationSelectionMode.CUBOID,
            List.of(bounds.min(), bounds.max()),
            BlockPos.ZERO,
            BlockPos.ZERO,
            0
         );
         return new ClientSelectionPart(
            0,
            ClientSelectionPart.Source.CLIPBOARD,
            selection,
            part.blocks,
            WorkspaceTransform.IDENTITY,
            false
         );
      }).toList();
   }

   public record Part(int originalId, Map<BlockPos, BlockSnapshot> blocks) {
      public Part {
         if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("Clipboard part is empty");
         }
         blocks = BlockPositionMaps.copyOf(blocks);
      }
   }
}
