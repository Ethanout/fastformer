package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.selection.OccupiedBlockBounds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Membership changes are atomic workspace events. Removal never splits a part. */
public final class SmartSelectionEdits {
   private SmartSelectionEdits() { }

   public static boolean editing(ClientOperationWorkspace workspace) {
      return active(workspace).isPresent();
   }

   private static java.util.Optional<ClientSelectionPart> active(ClientOperationWorkspace workspace) {
      return workspace.selections().topPart().filter(ClientSelectionPart::smartEditable);
   }

   public static OperationSelectionVolume volume(Map<BlockPos, ClientBlockSnapshot> blocks) {
      var bounds = OccupiedBlockBounds.from(blocks.keySet()).orElseThrow().aabb();
      return new OperationSelectionVolume(OperationSelectionMode.SMART, bounds, null, List.of(), 0, null, null);
   }

   public static boolean add(ClientOperationWorkspace workspace, Map<BlockPos, ClientBlockSnapshot> region) {
      if (region.isEmpty()) return false;
      var current = active(workspace).orElse(null);
      if (current == null) return workspace.addParts(List.of(new ClientSelectionPart(0,
         ClientSelectionPart.Source.WORLD, volume(region), region, WorkspaceTransform.IDENTITY, false)));
      if (!workspace.beginEdit()) return false;
      Map<BlockPos, ClientBlockSnapshot> merged = new LinkedHashMap<>(region);
      // Existing snapshots remain authoritative even if a neighbor changed after selection.
      merged.putAll(current.blocks());
      workspace.updatePart(current.withSelection(volume(merged)).withBlocks(merged));
      workspace.selectOnlyDuringEdit(current.id());
      workspace.finishEdit();
      return true;
   }

   public static boolean remove(ClientOperationWorkspace workspace, BlockPos position, boolean singleBlock) {
      var part = active(workspace).filter(candidate -> candidate.blocks().containsKey(position)).orElse(null);
      if (part == null || !workspace.beginEdit()) return false;
      var removed = singleBlock ? java.util.Set.of(position)
         : workspace.smartTopology(part).membersAt(position);
      Map<BlockPos, ClientBlockSnapshot> remaining = new LinkedHashMap<>(part.blocks());
      remaining.keySet().removeAll(removed);
      if (remaining.isEmpty()) workspace.removePartDuringEdit(part.id());
      else workspace.updatePart(part.withSelection(volume(remaining)).withBlocks(remaining));
      return workspace.finishEdit();
   }

   /** Single-cell expansion requires an editable neighbor and never replaces its snapshot. */
   public static boolean addCell(ClientOperationWorkspace workspace, BlockPos position, ClientBlockSnapshot snapshot) {
      var current = active(workspace).orElse(null);
      if (snapshot == null || current == null || current.blocks().containsKey(position)) return false;
      var cell = Map.of(position, snapshot);
      if (!touches(current.blocks(), cell)) return false;
      return add(workspace, cell);
   }

   public static boolean fix(ClientOperationWorkspace workspace) {
      var current = active(workspace).orElse(null);
      if (current == null || !workspace.beginEdit()) return false;
      workspace.updatePart(current.fixed());
      return workspace.finishEdit();
   }

   private static boolean touches(Map<BlockPos, ?> first, Map<BlockPos, ?> second) {
      Map<BlockPos, ?> smaller = first.size() < second.size() ? first : second;
      Map<BlockPos, ?> larger = smaller == first ? second : first;
      for (BlockPos position : smaller.keySet()) {
         if (larger.containsKey(position)) return true;
         for (Direction direction : Direction.values()) if (larger.containsKey(position.relative(direction))) return true;
      }
      return false;
   }
}
