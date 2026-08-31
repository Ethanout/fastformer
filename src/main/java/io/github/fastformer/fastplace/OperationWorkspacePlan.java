package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Untrusted client description of a workspace. Validation is mandatory before execution. */
public record OperationWorkspacePlan(List<Part> parts) {
   public OperationWorkspacePlan {
      parts = parts == null ? List.of() : List.copyOf(parts);
   }

   public record Part(
      int id,
      ClientSelectionPart.Source source,
      Map<BlockPos, ClientBlockSnapshot> blocks,
      WorkspaceTransform transform,
      boolean pendingDelete
   ) {
      public Part {
         LinkedHashMap<BlockPos, ClientBlockSnapshot> copy = new LinkedHashMap<>();
         if (blocks != null) {
            blocks.forEach((pos, snapshot) -> copy.put(pos.immutable(), snapshot));
         }
         blocks = Map.copyOf(copy);
      }

      public Part withTransform(WorkspaceTransform value) {
         return new Part(this.id, this.source, this.blocks, value, this.pendingDelete);
      }

      public Part withBlocks(Map<BlockPos, ClientBlockSnapshot> value) {
         return new Part(this.id, this.source, value, this.transform, this.pendingDelete);
      }
   }
}
