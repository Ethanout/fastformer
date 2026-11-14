package io.github.fastformer.client.preview.selection;

import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.common.geometry.controlpoint.ControlPoint;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.selection.geometry.SelectionPrism;
import io.github.fastformer.common.selection.smart.SmartSelectionTopology;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.world.BlockPositionMaps;
import io.github.fastformer.common.world.BlockPositionSets;
import io.github.fastformer.common.world.BlockSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import java.util.Collections;
import java.util.LinkedHashMap;
import net.minecraft.world.phys.Vec3;

/** Complete session facts for all selection passes; no mutable workspace or input owner is retained. */
public record SelectionPreviewModel(SelectionPreviewInput input, Workspace workspace, Remote remote, Draft draft,
   Candidate candidate, Modifiers modifiers, Isolation isolation) {
   public record Workspace(List<ClientSelectionPart> parts, Set<Integer> selectedIds, boolean locked,
      SelectionInteractionScene scene, OperationInteractionIntent pointer,
      Map<Integer, Map<BlockPos, BlockSnapshot>> blocks,
      Map<Integer, SmartSelectionTopology> topologies, Set<Integer> movingIds, Set<Integer> hoveredLabels,
      Map<Integer, AxisGizmo.HandleKey> activeKeys, AxisGizmo.HandleKey groupKey, boolean groupGesture,
      boolean altFocused, boolean smartEditing, Set<BlockPos> hiddenSourceBlocks) {
      public Workspace {
         parts = List.copyOf(parts);
         selectedIds = Set.copyOf(selectedIds);
         var frozen = new LinkedHashMap<Integer, Map<BlockPos, BlockSnapshot>>();
         blocks.forEach((id, values) -> frozen.put(id, BlockPositionMaps.copyOf(values)));
         blocks = Collections.unmodifiableMap(frozen);
         topologies = Map.copyOf(topologies);
         movingIds = Set.copyOf(movingIds);
         hoveredLabels = Set.copyOf(hoveredLabels);
         activeKeys = Map.copyOf(activeKeys);
         hiddenSourceBlocks = BlockPositionSets.copyOf(hiddenSourceBlocks);
      }
      public boolean isEmpty() { return parts.isEmpty(); }
   }
   public record Remote(boolean ready, int hoveredPoint, SelectionPrism.EdgeInsertion edgeInsertion, BlockPos candidate,
      SelectionPrism.GridPlane pointPlane, SelectionPrism.GridLine pointLine, BlockPos pointTarget, float gizmoAlpha,
      Vec3 eye, Surface surface) {}
   public record Surface(List<BlockPos> points, OperationSelectionVolume volume, BlockPos candidate,
      boolean edgeInsertion, List<ControlPoint> controls) {
      public Surface { points = List.copyOf(points); controls = List.copyOf(controls); }
   }
   public record Draft(List<BlockPos> points, OperationSelectionVolume volume, List<ControlPoint> controls) {
      public Draft {
         points = points.stream().map(BlockPos::immutable).toList();
         controls = List.copyOf(controls);
      }
   }
   public record Candidate(BlockPos position, ShapeShellMesh.Mesh mesh, String hint) {}
   public record Modifiers(boolean modifier, boolean control, boolean sneaking) {}
   public record Isolation(boolean active, BlockPos target) {}
}
