package io.github.fastformer.client.preview.selection;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.client.operation.selection.ClientSelectionSession.DraftState;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.common.selection.geometry.OperationGeometry;
import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SelectionPreviewModelTest {
   @Test void workspaceFactsOwnTheirCollectionsAndHiddenCoordinates() {
      var selected = new HashSet<>(Set.of(1));
      var position = new BlockPos.MutableBlockPos(1, 2, 3);
      var source = new HashMap<BlockPos, BlockSnapshot>();
      var hidden = new HashSet<BlockPos>(Set.of(position));
      var blocks = new HashMap<Integer, Map<BlockPos, BlockSnapshot>>();
      blocks.put(1, source);
      var model = new SelectionPreviewModel.Workspace(new ArrayList<>(), selected, false,
         SelectionInteractionScene.empty(UUID.randomUUID()), null, blocks, Map.of(), selected, Set.of(), Map.of(), null,
         false, false, false, hidden);
      selected.clear();
      source.clear();
      blocks.clear();
      hidden.clear();
      position.set(9, 9, 9);
      assertEquals(Set.of(1), model.selectedIds());
      assertEquals(Set.of(1), model.movingIds());
      assertEquals(Set.of(1), model.blocks().keySet());
      assertEquals(Set.of(new BlockPos(1, 2, 3)), model.hiddenSourceBlocks());
      assertThrows(UnsupportedOperationException.class, () -> model.blocks().clear());
      assertThrows(UnsupportedOperationException.class, () -> model.blocks().get(1).clear());
   }

   @Test void draggedFaceIsProjectedOntoTheCapturedAdjustedBounds() {
      var dragged = new OperationGeometry.RayHit(new Vec3(-5, 20, -8), new Vec3(1, 0, 0), 12, 0);
      var input = input(dragged, null);
      var volume = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(1, 2, 3), null, null);
      var result = input.faceTarget(volume);
      assertEquals(new Vec3(2, 3, 0), result.point());
      assertEquals(new Vec3(1, 0, 0), result.normal());
      assertEquals(12, result.distance());
      assertEquals(0, result.axis());
   }

   @Test void noDragUsesTheCapturedFallbackAndNoVolumeKeepsTheDrag() {
      var fallback = new OperationGeometry.RayHit(Vec3.ZERO, new Vec3(0, 1, 0), 3, 1);
      assertSame(fallback, input(null, fallback).faceTarget(null));
      assertSame(fallback, input(fallback, null).faceTarget(null));
      assertNull(input(null, null).faceTarget(null));
   }

   private static SelectionPreviewInput input(OperationGeometry.RayHit dragged, OperationGeometry.RayHit fallback) {
      return new SelectionPreviewInput(OperationPreviewPayload.inactive(), null, null, dragged, fallback, null, null, false, false);
   }

   @Test void emptyDraftLeavesTheCreationCandidateToItsOwnPreview() {
      var state = new DraftState(OperationSelectionMode.CUBOID, List.of(), 0, null, null);
      var model = SelectionPreviewModels.draft(state, new OperationInteractionIntent.CreateSelection(BlockPos.ZERO));
      assertTrue(model.points().isEmpty());
      assertNull(model.volume());
   }

   @Test void draftVolumeAndControlPointsAreBuiltBeforeDrawing() {
      var candidate = new BlockPos.MutableBlockPos(2, 3, 4);
      var state = new DraftState(OperationSelectionMode.CUBOID, List.of(BlockPos.ZERO), 0, BlockPos.ZERO, BlockPos.ZERO);
      var model = SelectionPreviewModels.draft(state, new OperationInteractionIntent.CreateSelection(candidate));
      candidate.set(8, 8, 8);
      assertEquals(List.of(BlockPos.ZERO, new BlockPos(2, 3, 4)), model.points());
      assertEquals(2, model.controls().size());
      assertEquals(new Vec3(3, 4, 5), model.volume().bounds().getMaxPosition());
      assertThrows(UnsupportedOperationException.class, () -> model.points().clear());
   }
}
