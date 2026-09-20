package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import net.minecraft.core.BlockPos;

/** Read-only presentation view of the currently captured pointer drag. */
public final class PointerDragSnapshotView {
   private PointerDragSnapshotView() { }

   public static WorkspaceFaceDrag face(ClientInputSession session) {
      return ClientOperationController.selectionGestures().face();
   }

   public static WorkspaceGizmoDrag gizmo(ClientInputSession session) {
      return ClientOperationController.selectionGestures().gizmo();
   }

   public static AxisGizmo.Axis axis(ClientInputSession session) {
      GeometryGizmoDrag drag = session.geometryGizmoDrag;
      return drag == null ? null : drag.axis();
   }

   public static AxisGizmo.Axis operationAxis(ClientInputSession session) {
      if (ClientOperationController.operationSelectionReady() && session.geometryGizmoDrag != null) {
         return session.geometryGizmoDrag.axis();
      }
      OperationDrag drag = session.operationDrag;
      return drag == null || drag.gizmoKey() == null ? null : drag.gizmoKey().axis();
   }

   public static AxisGizmo.Operation operationOperation(ClientInputSession session) {
      if (ClientOperationController.operationSelectionReady() && session.geometryGizmoDrag != null) {
         return session.geometryGizmoDrag.operation();
      }
      OperationDrag drag = session.operationDrag;
      return drag == null || drag.gizmoKey() == null ? null : drag.gizmoKey().operation();
   }

   public static AxisGizmo.HandleKey operationKey(ClientInputSession session) {
      WorkspaceGizmoDrag workspace = gizmo(session);
      if (workspace != null) {
         return new AxisGizmo.HandleKey(workspace.operation(), workspace.axis(), workspace.direction());
      }
      if (ClientOperationController.operationSelectionReady() && session.geometryGizmoDrag != null) {
         return new AxisGizmo.HandleKey(session.geometryGizmoDrag.operation(), session.geometryGizmoDrag.axis(), session.geometryGizmoDrag.direction());
      }
      return session.operationDrag == null ? null : session.operationDrag.gizmoKey();
   }

   public static int operationSteps(ClientInputSession session) {
      WorkspaceGizmoDrag workspace = gizmo(session);
      if (workspace != null) return workspace.sentSteps();
      if (ClientOperationController.operationSelectionReady() && session.geometryGizmoDrag != null) return session.geometryGizmoDrag.sentSteps();
      return session.operationDrag == null || session.operationDrag.gizmoKey() == null ? 0 : session.operationDrag.sentSteps();
   }

   public static double operationBaseValue(ClientInputSession session) {
      if (ClientOperationController.operationSelectionReady() && session.geometryGizmoDrag != null) return session.geometryGizmoDrag.baseValue();
      return session.operationDrag == null || session.operationDrag.gizmoKey() == null ? 0.0 : session.operationDrag.gizmoBaseValue();
   }

   public static AxisGizmo.HandleKey geometryKey(ClientInputSession session) {
      GeometryGizmoDrag drag = session.geometryGizmoDrag;
      return drag == null ? null : new AxisGizmo.HandleKey(drag.operation(), drag.axis(), drag.direction());
   }

   public static boolean workspaceGizmoMatches(ClientInputSession session, int partId, boolean common) {
      WorkspaceGizmoDrag drag = gizmo(session);
      return drag != null && drag.common() == common && (common || drag.partId() == partId);
   }

   public static AxisGizmo.Operation operation(ClientInputSession session) {
      GeometryGizmoDrag drag = session.geometryGizmoDrag;
      return drag == null ? null : drag.operation();
   }

   public static int steps(ClientInputSession session) {
      GeometryGizmoDrag drag = session.geometryGizmoDrag;
      return drag == null ? 0 : drag.sentSteps();
   }

   public static double baseValue(ClientInputSession session) {
      GeometryGizmoDrag drag = session.geometryGizmoDrag;
      return drag == null ? 0.0 : drag.baseValue();
   }

   public static boolean modifierHeld(ClientInputSession session) { return session.modifier.held(); }

   public static OperationGeometry.RayHit faceHit(ClientInputSession session) {
      WorkspaceFaceDrag drag = face(session);
      return drag == null ? null : drag.hit();
   }

   public static OperationGeometry.RayHit operationFaceHit(ClientInputSession session) {
      return session.operationDrag == null ? null : session.operationDrag.faceHit();
   }

   public static int facePartId(ClientInputSession session) {
      WorkspaceFaceDrag drag = face(session);
      return drag == null ? 0 : drag.baseline().id();
   }

   public static SelectionPrism.GridPlane pointPlane(ClientInputSession session) {
      return session.operationPointDrag == null ? null : session.operationPointDrag.plane();
   }

   public static SelectionPrism.GridLine pointLine(ClientInputSession session) {
      return session.operationPointDrag == null ? null : session.operationPointDrag.line();
   }

   public static BlockPos pointTarget(ClientInputSession session) {
      return session.operationPointDrag == null ? null : session.operationPointDrag.sentTarget();
   }
}
