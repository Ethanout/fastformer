package io.github.fastformer.workspace.submission;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.fastplace.geometry.BlockPositionMaps;
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
         blocks = blocks == null ? Map.of() : BlockPositionMaps.copyOf(blocks);
      }

      public Part withTransform(WorkspaceTransform value) {
         return new Part(this.id, this.source, this.blocks, value, this.pendingDelete);
      }

      public Part withBlocks(Map<BlockPos, ClientBlockSnapshot> value) {
         return new Part(this.id, this.source, value, this.transform, this.pendingDelete);
      }
   }
}
