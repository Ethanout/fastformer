package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PointerDragSnapshotViewTest {
   @Test
   void operationPresentationComesFromThePassedOwnerWithoutLookingUpGlobalSelection() {
      var captured = new ClientInputSession();
      capture(captured, PointerGestureState.Kind.OPERATION_GIZMO);
      var other = new ClientInputSession();

      assertEquals(Axis.Y, PointerDragSnapshotView.operationAxis(captured));
      assertEquals(AxisGizmo.Operation.SCALE, PointerDragSnapshotView.operationOperation(captured));
      assertEquals(3, PointerDragSnapshotView.operationSteps(captured));
      assertEquals(2.0, PointerDragSnapshotView.operationBaseValue(captured));
      assertNotNull(PointerDragSnapshotView.operationKey(captured));
      assertNull(PointerDragSnapshotView.operationAxis(other));
      assertNull(PointerDragSnapshotView.operationKey(other));
   }

   @Test
   void buildingAndReplacedCapturesCannotMasqueradeAsOperationGizmos() {
      var session = new ClientInputSession();
      capture(session, PointerGestureState.Kind.BUILDING_GEOMETRY);
      assertNull(PointerDragSnapshotView.operationAxis(session));
      capture(session, PointerGestureState.Kind.OPERATION_GIZMO);
      session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      assertNull(PointerDragSnapshotView.operationKey(session));
      assertEquals(0, PointerDragSnapshotView.operationSteps(session));
   }

   private static void capture(ClientInputSession session, PointerGestureState.Kind kind) {
      long token = session.pointerGesture.begin(kind);
      session.geometryGizmoDrag = new GeometryGizmoDrag(AxisGizmo.Operation.SCALE, Axis.Y, Vec3.ZERO,
         new Vec3(0, 1, 0), 3, 2.0, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, AxisGizmo.Direction.POSITIVE, 0)
         .withCapture(token);
   }
}
