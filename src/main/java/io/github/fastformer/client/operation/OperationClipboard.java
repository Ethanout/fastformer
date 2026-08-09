package io.github.fastformer.client.operation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Persistent resolved contents copied from the current client preview. */
public record OperationClipboard(List<Part> parts) {
   public static final int VERSION = 1;

   public OperationClipboard {
      parts = parts == null ? List.of() : List.copyOf(parts);
      if (parts.isEmpty() || parts.size() > ClientOperationWorkspace.MAX_PARTS) {
         throw new IllegalArgumentException("Clipboard must contain between one and ten parts");
      }
   }

   public static Optional<OperationClipboard> fromWorkspace(ClientOperationWorkspace workspace) {
      List<Part> copied = workspace.selectedParts().stream()
         .map(part -> new Part(part.id(), WorkspacePreviewComposer.resolve(part)))
         .filter(part -> !part.blocks().isEmpty())
         .toList();
      return copied.isEmpty() ? Optional.empty() : Optional.of(new OperationClipboard(copied));
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
         var selection = io.github.fastformer.fastplace.OperationSelectionVolume.create(
            io.github.fastformer.fastplace.OperationSelectionMode.CUBOID,
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

   public record Part(int originalId, Map<BlockPos, ClientBlockSnapshot> blocks) {
      public Part {
         if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("Clipboard part is empty");
         }
         LinkedHashMap<BlockPos, ClientBlockSnapshot> copy = new LinkedHashMap<>();
         blocks.forEach((pos, snapshot) -> copy.put(pos.immutable(), snapshot));
         blocks = Map.copyOf(copy);
      }
   }
}
