package io.github.fastformer.client.operation.workspace;

import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.preview.WorkspaceScene;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;

/** Deletes selected world sources and preserves every unselected workspace part. */
public final class SelectionDeletionPlan {
   private SelectionDeletionPlan() { }
   public static List<OperationWorkspacePlan.Part> parts(List<ClientSelectionPart> parts, Set<Integer> selected) {
      var scene = new WorkspaceScene<ClientBlockSnapshot>(value -> true);
      var sources = new LinkedHashMap<BlockPos, ClientBlockSnapshot>();
      int id = 0;
      for (var part : parts) {
         if (!selected.contains(part.id()) || part.source() != ClientSelectionPart.Source.WORLD) continue;
         if (id == 0) id = part.id();
         sources.putAll(part.sourceSnapshot().isEmpty() ? part.blocks() : part.sourceSnapshot());
      }
      scene.clearSources(sources.keySet());
      sources.keySet().retainAll(scene.exclusiveSources(protectedSources(parts, selected)));
      return sources.isEmpty() ? List.of() : List.of(new OperationWorkspacePlan.Part(id, ClientSelectionPart.Source.WORLD,
         sources, WorkspaceTransform.IDENTITY, true));
   }

   public static Set<BlockPos> protectedSources(List<ClientSelectionPart> parts, Set<Integer> selected) {
      return parts.stream().filter(part -> !selected.contains(part.id()))
         .filter(part -> part.source() == ClientSelectionPart.Source.WORLD)
         .flatMap(part -> (part.sourceSnapshot().isEmpty() ? part.blocks() : part.sourceSnapshot()).keySet().stream())
         .collect(java.util.stream.Collectors.toSet());
   }

   public static ClientOperationWorkspace.DraftState remaining(ClientOperationWorkspace workspace, Set<Integer> deleted) {
      var parts = workspace.parts().stream().filter(part -> !deleted.contains(part.id())).toList();
      var selected = workspace.selectedIds().stream().filter(id -> !deleted.contains(id)).collect(java.util.stream.Collectors.toSet());
      return new ClientOperationWorkspace.DraftState(parts, selected, selected.contains(workspace.activeId()) ? workspace.activeId() : 0);
   }
}
