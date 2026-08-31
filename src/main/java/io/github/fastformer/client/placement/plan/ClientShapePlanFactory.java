package io.github.fastformer.client.placement.plan;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Converts a resolved client shape into the transport plan sent to the server. */
public final class ClientShapePlanFactory {
   private ClientShapePlanFactory() {
   }

   public static OperationWorkspacePlan singlePart(Set<BlockPos> blocks, BlockState state) {
      if (blocks == null || blocks.isEmpty() || state == null || state.isAir()) {
         return new OperationWorkspacePlan(List.of());
      }
      Map<BlockPos, ClientBlockSnapshot> snapshots = new LinkedHashMap<>();
      for (BlockPos block : blocks) {
         if (block != null) {
            snapshots.put(block.immutable(), new ClientBlockSnapshot(state, null));
         }
      }
      if (snapshots.isEmpty()) {
         return new OperationWorkspacePlan(List.of());
      }
      return new OperationWorkspacePlan(List.of(new OperationWorkspacePlan.Part(
         1,
         ClientSelectionPart.Source.CLIPBOARD,
         snapshots,
         WorkspaceTransform.IDENTITY,
         false
      )));
   }
}
