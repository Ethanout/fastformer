package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class OperationSelectionSessionTest {
   @Test
   void readySelectionExposesOperationsWithoutConfirmation() {
      OperationSession session = new OperationSession();
      session.setFirst(BlockPos.ZERO);
      session.setSecond(new BlockPos(2, 2, 2));

      assertTrue(session.operationReady());
      assertFalse(session.adjustmentStarted());
      assertEquals(OperationStackRegion.origin(), session.stackRegion());
   }

   @Test
   void transformDragRecomputesFromBaselineAndCommitsOneAdjustment() {
      OperationSession session = readyCuboid();
      List<BlockPos> selection = session.points();

      assertTrue(session.beginTransform(AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1));
      assertTrue(session.updateTransform(3));
      assertEquals(4L, session.stackRegion().cellCount());
      assertTrue(session.updateTransform(1));
      assertEquals(2L, session.stackRegion().cellCount());
      assertTrue(session.finishTransform());
      assertTrue(session.adjustmentStarted());

      assertTrue(session.undoAdjustment());
      assertEquals(OperationStackRegion.origin(), session.stackRegion());
      assertEquals(selection, session.points());
      assertFalse(session.adjustmentStarted());
      assertFalse(session.undoAdjustment());
   }

   @Test
   void draggingBackToZeroDoesNotCreateAdjustmentHistory() {
      OperationSession session = readyCuboid();

      assertTrue(session.beginTransform(AxisGizmo.Operation.MOVE, AxisGizmo.Axis.Z, -1));
      assertTrue(session.updateTransform(5));
      assertTrue(session.updateTransform(0));
      assertFalse(session.finishTransform());

      assertEquals(BlockPos.ZERO, session.translation());
      assertFalse(session.adjustmentStarted());
      assertFalse(session.undoAdjustment());
   }

   @Test
   void stackDragBackToZeroDoesNotChangeModeOrCreateHistory() {
      OperationSession session = readyCuboid();

      assertTrue(session.beginTransform(AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1));
      assertTrue(session.updateTransform(2));
      assertTrue(session.updateTransform(0));
      assertFalse(session.finishTransform());

      assertEquals(OperationMode.MOVE, session.mode());
      assertEquals(OperationStackRegion.origin(), session.stackRegion());
      assertFalse(session.adjustmentStarted());
   }

   @Test
   void laterRepeatOnTheSameAxisAddsCopiesToItsBaseline() {
      OperationSession session = readyCuboid();

      session.beginTransform(AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1);
      session.updateTransform(3);
      session.finishTransform();
      session.beginTransform(AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1);
      session.updateTransform(1);
      session.finishTransform();

      assertEquals(5L, session.stackRegion().cellCount());
      assertTrue(session.undoAdjustment());
      assertEquals(4L, session.stackRegion().cellCount());
   }

   @Test
   void movingAfterStackPreservesTheCompleteRegion() {
      OperationSession session = readyCuboid();
      session.beginTransform(AxisGizmo.Operation.SCALE, AxisGizmo.Axis.Y, 1);
      session.updateTransform(2);
      session.finishTransform();
      OperationStackRegion stacked = session.stackRegion();

      session.beginTransform(AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, -1);
      session.updateTransform(4);
      session.finishTransform();

      assertEquals(new BlockPos(-4, 0, 0), session.translation());
      assertEquals(stacked, session.stackRegion());
   }

   @Test
   void historyLimitAlsoTrimsIndependentAdjustmentHistory() {
      OperationSession session = readyCuboid();
      for (int amount = 1; amount <= 3; amount++) {
         session.beginTransform(AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, 1);
         session.updateTransform(amount);
         session.finishTransform();
      }

      session.updateHistoryLimit(1);

      assertTrue(session.undoAdjustment());
      assertFalse(session.undoAdjustment());
   }

   @Test
   void changingSelectionModeClearsAllAdjustmentsAndTheirHistory() {
      OperationSession session = readyCuboid();
      session.beginTransform(AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, 1);
      session.updateTransform(3);
      session.finishTransform();

      session.setSelectionMode(OperationSelectionMode.PRISM);

      assertFalse(session.adjustmentStarted());
      assertEquals(BlockPos.ZERO, session.translation());
      assertEquals(OperationStackRegion.origin(), session.stackRegion());
      assertFalse(session.undoAdjustment());
   }

   @Test
   void makingAPrismIncompleteClearsAllAdjustmentsAndTheirHistory() {
      OperationSession session = new OperationSession(OperationSelectionMode.PRISM);
      session.setFirst(BlockPos.ZERO);
      session.setSecond(new BlockPos(4, 0, 0));
      session.addSelectionPoint(new BlockPos(0, 0, 4));
      session.closePrismBase();
      session.addSelectionPoint(new BlockPos(0, 5, 0));
      session.beginTransform(AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1);
      session.updateTransform(2);
      session.finishTransform();

      assertTrue(session.removePoint(3));

      assertFalse(session.selectionReady());
      assertFalse(session.adjustmentStarted());
      assertEquals(OperationStackRegion.origin(), session.stackRegion());
      assertFalse(session.undoAdjustment());
   }

   @Test
   void undoingSelectionModeChangeCannotResurrectTransformFields() {
      OperationSession session = readyCuboid();
      session.beginTransform(AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, 1);
      session.updateTransform(3);
      session.finishTransform();

      session.beginEdit();
      session.setSelectionMode(OperationSelectionMode.PRISM);
      assertTrue(session.commitEdit());
      assertTrue(session.undoStep());

      assertEquals(OperationSelectionMode.CUBOID, session.selectionMode());
      assertEquals(BlockPos.ZERO, session.translation());
      assertEquals(OperationStackRegion.origin(), session.stackRegion());
      assertFalse(session.adjustmentStarted());
   }

   @Test
   void undoingPointRemovalCannotResurrectTransformFields() {
      OperationSession session = new OperationSession(OperationSelectionMode.PRISM);
      session.setFirst(BlockPos.ZERO);
      session.setSecond(new BlockPos(4, 0, 0));
      session.addSelectionPoint(new BlockPos(0, 0, 4));
      session.closePrismBase();
      session.addSelectionPoint(new BlockPos(0, 5, 0));
      session.beginTransform(AxisGizmo.Operation.MOVE, AxisGizmo.Axis.Z, 1);
      session.updateTransform(2);
      session.finishTransform();

      session.beginEdit();
      session.removePoint(3);
      assertTrue(session.commitEdit());
      assertTrue(session.undoStep());

      assertTrue(session.selectionReady());
      assertEquals(BlockPos.ZERO, session.translation());
      assertEquals(OperationStackRegion.origin(), session.stackRegion());
      assertFalse(session.adjustmentStarted());
   }

   private static OperationSession readyCuboid() {
      OperationSession session = new OperationSession();
      session.setFirst(BlockPos.ZERO);
      session.setSecond(new BlockPos(2, 2, 2));
      return session;
   }

   @Test
   void selectionModesCycleOnlyBetweenCuboidAndPrism() {
      OperationSession session = new OperationSession();

      assertEquals(OperationSelectionMode.CUBOID, session.selectionMode());
      session.cycleSelectionMode();
      assertEquals(OperationSelectionMode.PRISM, session.selectionMode());
      session.cycleSelectionMode();
      assertEquals(OperationSelectionMode.CUBOID, session.selectionMode());
   }

   @Test
   void cyclingSelectionModesPreservesCuboidButClearsPrismState() {
      OperationSession session = new OperationSession();
      BlockPos cuboidFirst = new BlockPos(1, 2, 3);
      BlockPos cuboidSecond = new BlockPos(8, 4, 2);
      session.setFirst(cuboidFirst);
      session.setSecond(cuboidSecond);
      assertTrue(session.selectionReady());

      session.cycleSelectionMode();

      assertEquals(OperationSelectionMode.PRISM, session.selectionMode());
      assertFalse(session.hasFirst());
      assertFalse(session.hasSecond());
      assertTrue(session.points().isEmpty());

      BlockPos prismFirst = new BlockPos(0, 0, 0);
      BlockPos prismSecond = new BlockPos(4, 0, 0);
      BlockPos prismThird = new BlockPos(2, 0, 3);
      BlockPos prismTop = new BlockPos(0, 5, 0);
      session.setFirst(prismFirst);
      session.setSecond(prismSecond);
      assertTrue(session.addSelectionPoint(prismThird));
      assertTrue(session.closePrismBase());
      assertTrue(session.addSelectionPoint(prismTop));
      assertTrue(session.selectPoint(2));
      assertTrue(session.selectionReady());

      session.cycleSelectionMode();

      assertEquals(OperationSelectionMode.CUBOID, session.selectionMode());
      assertEquals(cuboidFirst, session.first());
      assertEquals(cuboidSecond, session.second());
      assertEquals(List.of(cuboidFirst, cuboidSecond), session.points());
      assertTrue(session.selectionReady());

      session.cycleSelectionMode();

      assertEquals(OperationSelectionMode.PRISM, session.selectionMode());
      assertTrue(session.points().isEmpty());
      assertEquals(0, session.prismBasePointCount());
      assertEquals(-1, session.selectedPointIndex());
      assertFalse(session.selectionReady());
   }

   @Test
   void clearingTheSessionClearsEverySelectionModeState() {
      OperationSession session = new OperationSession();
      session.setFirst(new BlockPos(1, 2, 3));
      session.setSecond(new BlockPos(4, 5, 6));
      session.cycleSelectionMode();
      session.setFirst(new BlockPos(7, 8, 9));
      session.setSecond(new BlockPos(10, 11, 12));

      session.clear();

      assertTrue(session.points().isEmpty());
      session.cycleSelectionMode();
      assertTrue(session.points().isEmpty());
   }

   @Test
   void cuboidCanEnterFromPointTwoBeforePointOne() {
      OperationSession session = new OperationSession();

      session.setSecond(new BlockPos(8, 4, 2));
      assertFalse(session.hasFirst());
      assertTrue(session.hasSecond());
      assertFalse(session.selectionReady());

      session.setFirst(new BlockPos(1, 2, 3));
      assertTrue(session.selectionReady());
      assertEquals(new BlockPos(1, 2, 3), session.first());
      assertEquals(new BlockPos(8, 4, 2), session.second());
   }

   @Test
   void operationHistoryCanUndoAndRedoTheFirstPoint() {
      OperationSession session = new OperationSession();
      session.beginEdit();
      session.setFirst(new BlockPos(1, 2, 3));
      assertTrue(session.commitEdit());

      assertTrue(session.undoStep());
      assertFalse(session.canUndoStep());
      assertFalse(session.undoStep());
      assertTrue(session.canRedoStep());
      assertTrue(session.redoStep());
      assertEquals(new BlockPos(1, 2, 3), session.first());
   }

   @Test
   void sessionHistoryUsesItsOwnLimitAndCanBeTrimmedImmediately() {
      OperationSession session = new OperationSession(OperationSelectionMode.CUBOID, 3);
      for (int x = 1; x <= 3; x++) {
         session.beginEdit();
         session.setFirst(new BlockPos(x, 0, 0));
         assertTrue(session.commitEdit());
      }

      session.updateHistoryLimit(1);

      assertTrue(session.undoStep());
      assertEquals(new BlockPos(2, 0, 0), session.first());
      assertFalse(session.undoStep());
   }

   @Test
   void middlePointExpandsCuboidByMovingTheClosestAnchors() {
      OperationSession session = new OperationSession();
      session.setFirst(new BlockPos(2, 3, 4));
      session.setSecond(new BlockPos(5, 6, 7));

      assertTrue(session.expandTo(new BlockPos(-1, 9, 6)));
      assertEquals(new BlockPos(-1, 3, 4), session.first());
      assertEquals(new BlockPos(5, 9, 7), session.second());
      assertEquals(BlockPos.ZERO, session.minOffset());
      assertEquals(BlockPos.ZERO, session.maxOffset());
   }

   @Test
   void faceAdjustmentMovesTheAnchorAlreadyClosestToThatFace() {
      OperationSession session = new OperationSession();
      session.setFirst(new BlockPos(8, 2, 9));
      session.setSecond(new BlockPos(3, 7, 4));

      assertTrue(session.extend(0, true, -2));
      assertEquals(new BlockPos(6, 2, 9), session.first());
      assertEquals(new BlockPos(3, 7, 4), session.second());

      assertTrue(session.extend(1, false, 2));
      assertEquals(new BlockPos(6, 0, 9), session.first());
      assertEquals(new BlockPos(3, 7, 4), session.second());
   }

   @Test
   void enterConfirmationDoesNotCreateAnOperationMutation() {
      OperationSession session = new OperationSession();
      session.setFirst(BlockPos.ZERO);
      session.setSecond(new BlockPos(3, 4, 5));

      session.beginEdit();
      assertTrue(session.confirmSelection());
      assertTrue(session.commitEdit());
      assertTrue(session.selectionConfirmed());
      assertEquals(BlockPos.ZERO, session.translation());
      assertEquals(BlockPos.ZERO, session.stackVector());

      assertFalse(session.extend(0, true, 1));
      assertTrue(session.selectionConfirmed());
      assertTrue(session.undoStep());
      assertFalse(session.selectionConfirmed());
      assertTrue(session.extend(0, true, 1));
   }

   @Test
   void confirmedSelectionScrollUpdatesMoveVector() {
      OperationSession session = new OperationSession();
      session.setFirst(BlockPos.ZERO);
      session.setSecond(new BlockPos(2, 1, 3));
      assertTrue(session.confirmSelection());

      session.beginEdit();
      session.scroll(new BlockPos(0, 0, 2));
      assertFalse(session.commitEdit());

      assertEquals(new BlockPos(0, 0, 2), session.translation());
      assertEquals(BlockPos.ZERO, session.stackVector());
   }

   @Test
   void confirmedSelectionScrollUpdatesStackVector() {
      OperationSession session = new OperationSession();
      session.setFirst(BlockPos.ZERO);
      session.setSecond(new BlockPos(2, 1, 3));
      assertTrue(session.confirmSelection());

      session.beginEdit();
      session.scroll(new BlockPos(1, 0, 0), true);
      assertFalse(session.commitEdit());

      assertEquals(BlockPos.ZERO, session.translation());
      assertEquals(new BlockPos(1, 0, 0), session.stackVector());
   }

   @Test
   void operationStageStartsAtTransformAndCombinesMoveRepeatAndRotation() {
      OperationSession session = new OperationSession();
      session.setFirst(BlockPos.ZERO);
      session.setSecond(new BlockPos(2, 2, 2));
      assertTrue(session.confirmSelection());
      assertEquals(OperationStageMode.TRANSFORM, session.stageMode());

      session.scroll(new BlockPos(2, 0, 0), false);
      session.scroll(new BlockPos(0, 1, 0), true);
      assertTrue(session.adjustOperationTransform(
         io.github.fastformer.fastplace.geometry.AxisGizmo.Operation.ROTATE,
         io.github.fastformer.fastplace.geometry.AxisGizmo.Axis.Z,
         256
      ));
      session.cycleStageMode();
      assertEquals(OperationStageMode.SWEEP, session.stageMode());
      session.cycleStageMode();
      assertEquals(OperationStageMode.LOFT, session.stageMode());
      session.cycleStageMode();

      assertEquals(OperationStageMode.TRANSFORM, session.stageMode());
      assertEquals(new BlockPos(2, 0, 0), session.translation());
      assertEquals(new BlockPos(0, 1, 0), session.stackVector());
      assertEquals(Math.PI / 2.0, session.rotation().z, 1.0E-9);
   }

   @Test
   void continuousFaceAdjustmentIsOneUndoableSelectionOperation() {
      OperationSession session = new OperationSession();
      session.setFirst(new BlockPos(0, 0, 0));
      session.setSecond(new BlockPos(4, 4, 4));
      List<BlockPos> before = session.points();

      session.beginEdit();
      assertTrue(session.extend(0, true, 2));
      assertTrue(session.extend(0, true, 3));
      assertTrue(session.commitEdit());
      List<BlockPos> adjusted = session.points();
      assertFalse(before.equals(adjusted));

      assertTrue(session.undoStep());
      assertEquals(before, session.points());
      assertTrue(session.redoStep());
      assertEquals(adjusted, session.points());
   }

   @Test
   void prismUsesAClosedPolygonBeforeAcceptingItsTopFace() {
      OperationSession session = new OperationSession();
      session.setSelectionMode(OperationSelectionMode.PRISM);
      session.setFirst(new BlockPos(0, 0, 0));
      session.setSecond(new BlockPos(4, 0, 0));
      assertTrue(session.addSelectionPoint(new BlockPos(3, 0, 3)));
      assertTrue(session.addSelectionPoint(new BlockPos(0, 0, 2)));

      assertEquals(OperationSelectionStage.FACE, session.selectionStage());
      assertTrue(session.closePrismBase());
      assertEquals(4, session.prismBasePointCount());
      assertEquals(OperationSelectionStage.HEIGHT, session.selectionStage());
      assertTrue(session.addSelectionPoint(new BlockPos(0, 5, 0)));
      assertEquals(OperationSelectionStage.READY, session.selectionStage());
      assertTrue(session.selectionReady());

      assertTrue(session.selectPoint(2));
      assertTrue(session.moveSelectedPoint(0, 2));
      assertEquals(new BlockPos(5, 0, 3), session.points().get(2));
      assertTrue(session.moveSelection(1, 3));
      assertEquals(new BlockPos(5, 3, 3), session.points().get(2));
   }

   @Test
   void removingAPrismPointKeepsListOrderAndUpdatesStages() {
      OperationSession session = new OperationSession();
      session.setSelectionMode(OperationSelectionMode.PRISM);
      session.setFirst(new BlockPos(0, 0, 0));
      session.setSecond(new BlockPos(4, 0, 0));
      session.addSelectionPoint(new BlockPos(4, 0, 4));
      session.addSelectionPoint(new BlockPos(0, 0, 4));
      assertTrue(session.closePrismBase());
      session.addSelectionPoint(new BlockPos(0, 5, 0));

      assertTrue(session.removePoint(0));
      assertEquals(new BlockPos(4, 0, 0), session.first());
      assertEquals(3, session.prismBasePointCount());
      assertEquals(OperationSelectionStage.READY, session.selectionStage());

      assertTrue(session.removePoint(3));
      assertEquals(OperationSelectionStage.HEIGHT, session.selectionStage());
   }

   @Test
   void indexedPointMovementDoesNotDependOnThePreviouslySelectedPoint() {
      OperationSession session = new OperationSession();
      session.setSelectionMode(OperationSelectionMode.PRISM);
      session.setFirst(new BlockPos(0, 0, 0));
      session.setSecond(new BlockPos(4, 0, 0));
      session.addSelectionPoint(new BlockPos(2, 0, 3));
      assertTrue(session.selectPoint(2));

      assertTrue(session.focusPoint(0));
      assertTrue(session.movePoint(0, 1, 2));

      assertEquals(new BlockPos(0, 2, 0), session.first());
      assertEquals(new BlockPos(4, 2, 0), session.second());
      assertEquals(new BlockPos(2, 2, 3), session.points().get(2));
      assertEquals(0, session.selectedPointIndex());
   }

   @Test
   void prismPointMovementUsesFrozenBasePlaneAndHeightLineConstraints() {
      OperationSession constrained = new OperationSession();
      constrained.setSelectionMode(OperationSelectionMode.PRISM);
      constrained.setFirst(new BlockPos(0, 0, 0));
      constrained.setSecond(new BlockPos(4, 0, 0));
      constrained.addSelectionPoint(new BlockPos(0, 4, 4));

      assertTrue(constrained.dragPointTo(1, new BlockPos(4, 5, 2), false));
      assertEquals(new BlockPos(4, 2, 2), constrained.second());

      OperationSession dualFirst = new OperationSession();
      dualFirst.setSelectionMode(OperationSelectionMode.PRISM);
      dualFirst.setFirst(new BlockPos(0, 0, 0));
      dualFirst.setSecond(new BlockPos(4, 0, 0));
      dualFirst.addSelectionPoint(new BlockPos(0, 4, 4));

      assertTrue(dualFirst.dragPointTo(0, new BlockPos(0, -4, 4), false));
      assertEquals(List.of(
         new BlockPos(0, -4, 4), new BlockPos(4, -4, 4), new BlockPos(0, 0, 8)
      ), dualFirst.points());
      assertTrue(dualFirst.dragPointTo(0, new BlockPos(2, 3, 3), false));
      assertEquals(List.of(
         new BlockPos(2, 3, 3), new BlockPos(6, 3, 3), new BlockPos(2, 7, 7)
      ), dualFirst.points());

      OperationSession heightOnly = new OperationSession();
      heightOnly.setSelectionMode(OperationSelectionMode.PRISM);
      heightOnly.setFirst(new BlockPos(0, 0, 0));
      heightOnly.setSecond(new BlockPos(4, 0, 0));
      heightOnly.addSelectionPoint(new BlockPos(0, 0, 4));
      assertTrue(heightOnly.closePrismBase());
      assertTrue(heightOnly.addSelectionPoint(new BlockPos(0, 5, 0)));

      assertTrue(heightOnly.dragPointTo(3, new BlockPos(3, 7, 1), false));
      assertEquals(new BlockPos(0, 7, 0), heightOnly.points().get(3));
   }

   @Test
   void firstPointMovesTheBaseAndOnlyPlanarMotionCarriesTheHeightPoint() {
      OperationSession session = new OperationSession();
      session.setSelectionMode(OperationSelectionMode.PRISM);
      session.setFirst(new BlockPos(0, 0, 0));
      session.setSecond(new BlockPos(4, 0, 0));
      session.addSelectionPoint(new BlockPos(0, 0, 4));
      assertTrue(session.closePrismBase());
      assertTrue(session.addSelectionPoint(new BlockPos(0, 5, 0)));

      assertTrue(session.dragPointTo(0, new BlockPos(2, 0, 0), false));
      assertEquals(List.of(
         new BlockPos(2, 0, 0), new BlockPos(6, 0, 0), new BlockPos(2, 0, 4), new BlockPos(2, 5, 0)
      ), session.points());

      assertTrue(session.dragPointTo(0, new BlockPos(2, 2, 0), false));
      assertEquals(List.of(
         new BlockPos(0, 2, 0), new BlockPos(4, 2, 0), new BlockPos(0, 2, 4), new BlockPos(2, 5, 0)
      ), session.points());
   }

   @Test
   void firstPointConstraintSwitchesContinueFromTheCurrentDragTarget() {
      OperationSession session = new OperationSession();
      session.setSelectionMode(OperationSelectionMode.PRISM);
      session.setFirst(new BlockPos(0, 0, 0));
      session.setSecond(new BlockPos(4, 0, 0));
      session.addSelectionPoint(new BlockPos(0, 0, 4));
      assertTrue(session.closePrismBase());
      assertTrue(session.addSelectionPoint(new BlockPos(0, 5, 0)));

      assertTrue(session.dragPointTo(
         0, new BlockPos(0, 2, 0), OperationPointDragConstraint.LINE, false
      ));
      assertEquals(List.of(
         new BlockPos(0, 2, 0), new BlockPos(4, 2, 0), new BlockPos(0, 2, 4), new BlockPos(0, 5, 0)
      ), session.points());

      assertTrue(session.dragPointTo(
         0, new BlockPos(2, 2, 0), OperationPointDragConstraint.PLANE, false
      ));
      assertEquals(List.of(
         new BlockPos(2, 2, 0), new BlockPos(6, 2, 0), new BlockPos(2, 2, 4), new BlockPos(2, 5, 0)
      ), session.points());

      assertTrue(session.dragPointTo(
         0, new BlockPos(2, 3, 0), OperationPointDragConstraint.LINE, true
      ));
      assertEquals(List.of(
         new BlockPos(2, 3, 0), new BlockPos(6, 3, 0), new BlockPos(2, 3, 4), new BlockPos(2, 5, 0)
      ), session.points());
   }

   @Test
   void confirmedSelectionIsLockedUntilUndoReturnsToEditing() {
      OperationSession session = new OperationSession();
      session.setFirst(new BlockPos(0, 0, 0));
      session.setSecond(new BlockPos(4, 4, 4));
      session.beginEdit();
      assertTrue(session.confirmSelection());
      assertTrue(session.commitEdit());

      assertFalse(session.extend(0, true, 1));
      assertFalse(session.moveSelection(1, 2));
      session.setFirst(new BlockPos(20, 20, 20));
      assertEquals(new BlockPos(0, 0, 0), session.first());

      assertTrue(session.undoStep());
      assertFalse(session.selectionConfirmed());
      assertEquals(List.of(new BlockPos(0, 0, 0), new BlockPos(4, 4, 4)), session.points());
      assertTrue(session.extend(0, true, 1));
   }

   @Test
   void prismEdgeInsertionPreservesOrderAndClosedHeightIndex() {
      OperationSession open = new OperationSession();
      open.setSelectionMode(OperationSelectionMode.PRISM);
      open.setFirst(new BlockPos(0, 0, 0));
      open.setSecond(new BlockPos(10, 0, 0));
      open.addSelectionPoint(new BlockPos(10, 0, 10));

      assertTrue(open.insertPoint(1, new BlockPos(5, 0, 0)));
      assertEquals(
         List.of(new BlockPos(0, 0, 0), new BlockPos(5, 0, 0), new BlockPos(10, 0, 0), new BlockPos(10, 0, 10)),
         open.points()
      );

      OperationSession closed = new OperationSession();
      closed.setSelectionMode(OperationSelectionMode.PRISM);
      closed.setFirst(new BlockPos(0, 0, 0));
      closed.setSecond(new BlockPos(10, 0, 0));
      closed.addSelectionPoint(new BlockPos(10, 0, 10));
      closed.addSelectionPoint(new BlockPos(0, 0, 10));
      assertTrue(closed.closePrismBase());
      assertTrue(closed.addSelectionPoint(new BlockPos(0, 5, 0)));

      assertTrue(closed.insertPoint(4, new BlockPos(0, 0, 5)));
      assertEquals(5, closed.prismBasePointCount());
      assertEquals(new BlockPos(0, 0, 5), closed.points().get(4));
      assertEquals(new BlockPos(0, 5, 0), closed.points().get(5));
   }
}
