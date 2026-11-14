package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.testkit.ClientScopes;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SelectionGestureOwnershipTest {
   @Test void pointerViewReadsItsBoundScopeAndResetReleasesOnlyThatScope() {
      var first = scope(); var second = scope();
      start(first); start(second);
      var firstDrag = first.operations().selectionGestures().gizmo();
      var secondDrag = second.operations().selectionGestures().gizmo();
      var secondEdit = second.workspace().activeEditToken();
      assertSame(first.operations().selectionGestures(), first.inputSession().selectionGestures());
      assertSame(firstDrag, PointerDragSnapshotView.gizmo(first.inputSession()));
      assertSame(secondDrag, PointerDragSnapshotView.gizmo(second.inputSession()));
      first.resetInput();
      assertFalse(first.workspace().editing());
      assertNull(PointerDragSnapshotView.gizmo(first.inputSession()));
      assertSame(secondDrag, PointerDragSnapshotView.gizmo(second.inputSession()));
      assertTrue(second.workspace().ownsEdit(secondEdit));
      second.resetInput();
   }

   @Test void leavingOperationsCancelsPointerAndEditTogether() {
      var scope = scope(); start(scope);
      scope.operations().leaveEnvironment();
      assertFalse(scope.workspace().editing());
      assertFalse(scope.inputSession().selectionGestureSnapshot().active());
      assertEquals(0, scope.inputSession().pointerGesture.activeToken());
   }

   private static ClientScope scope() {
      var scope = ClientScopes.create(UUID.randomUUID());
      var volume = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO);
      scope.workspace().addParts(List.of(new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, volume,
         Map.of(), WorkspaceTransform.IDENTITY, false)));
      scope.operations().selectAllWorkspaceParts();
      return scope;
   }
   private static void start(ClientScope scope) {
      var gizmo = new AxisGizmo(Vec3.ZERO, 1.0, 0.1);
      var handle = gizmo.handles().stream().filter(value -> value.operation() == AxisGizmo.Operation.MOVE).findFirst().orElseThrow();
      var target = new OperationInteractionIntent.Gizmo(0, true, gizmo, new AxisGizmo.Hit(handle, Vec3.ZERO, 1, 0.1));
      assertTrue(scope.inputSession().selectionGestures().beginGizmo(FastPlaceClientInput.selectionGizmoPress(target, 0, false)));
   }
}
