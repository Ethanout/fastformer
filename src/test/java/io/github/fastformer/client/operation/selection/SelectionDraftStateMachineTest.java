package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SelectionDraftStateMachineTest {
   private static final BlockPos SECOND = new BlockPos(2, 3, 4);

   @Test
   void inspectionDoesNotPublishTheProposedSnapshot() {
      var machine = new SelectionDraftStateMachine();
      var before = machine.snapshot();
      var event = click(0, BlockPos.ZERO, false);
      var decision = machine.inspect(event);
      assertEquals(SelectionDraftResult.UPDATED, decision.result());
      assertEquals(List.of(BlockPos.ZERO), decision.next().points());
      assertSame(before, machine.snapshot());
      assertEquals(SelectionDraftStateMachine.Phase.CUBOID_EMPTY, machine.phase());
      assertEquals(SelectionDraftResult.UPDATED, machine.onEvent(event));
      assertEquals(decision.next(), machine.snapshot());
      assertTrue(before.points().isEmpty());
      assertEquals(SelectionDraftStateMachine.Phase.CUBOID_FIRST, machine.phase());
   }

   @Test
   void sequentialEventsAdvanceCuboidPhasesWithoutATickBarrier() {
      var machine = new SelectionDraftStateMachine();
      machine.onEvent(click(0, BlockPos.ZERO, false));
      var first = machine.snapshot();
      assertEquals(SelectionDraftResult.READY, machine.onEvent(click(1, SECOND, false)));
      var bounds = machine.snapshot();
      assertEquals(SelectionDraftStateMachine.Phase.CUBOID_BOUNDS, machine.phase());
      assertEquals(List.of(BlockPos.ZERO), first.points());
      assertEquals(SECOND, bounds.maxPoint());
      machine.onEvent(click(0, new BlockPos(9, 9, 9), false));
      assertEquals(SelectionDraftStateMachine.Phase.CUBOID_FIRST, machine.phase());
      assertEquals(List.of(BlockPos.ZERO, SECOND), bounds.points());
      assertEquals(SECOND, bounds.maxPoint());
   }

   @Test
   void rejectedEventsKeepTheExactSnapshot() {
      var machine = new SelectionDraftStateMachine();
      var empty = machine.snapshot();
      assertEquals(SelectionDraftResult.REJECTED, machine.onEvent(click(1, BlockPos.ZERO, false)));
      assertSame(empty, machine.snapshot());
      assertEquals(SelectionDraftResult.REJECTED, machine.onEvent(null));
      assertSame(empty, machine.snapshot());
      machine.onEvent(click(2, BlockPos.ZERO, true));
      machine.onEvent(click(2, SECOND, true));
      var complete = machine.snapshot();
      assertEquals(SelectionDraftResult.REJECTED, machine.onEvent(click(2, BlockPos.ZERO, true)));
      assertSame(complete, machine.snapshot());
      assertEquals(SelectionDraftResult.REJECTED, machine.onEvent(click(1, SECOND, false)));
      assertSame(complete, machine.snapshot());
   }

   @Test
   void prismCloseHeightAndBacktrackUseTheirCurrentPhase() {
      var machine = new SelectionDraftStateMachine();
      machine.setMode(OperationSelectionMode.PRISM);
      machine.onEvent(click(1, BlockPos.ZERO, false));
      machine.onEvent(click(1, new BlockPos(3, 0, 0), false));
      machine.onEvent(click(1, new BlockPos(0, 0, 3), false));
      var open = machine.snapshot();
      var close = machine.inspect(click(1, BlockPos.ZERO, false));
      assertEquals(3, close.next().prismBaseCount());
      assertSame(open, machine.snapshot());
      machine.onEvent(click(1, BlockPos.ZERO, false));
      assertEquals(SelectionDraftStateMachine.Phase.PRISM_HEIGHT, machine.phase());
      assertEquals(SelectionDraftResult.READY, machine.onEvent(click(2, SECOND, false)));
      var ready = machine.snapshot();
      machine.onEvent(click(0, BlockPos.ZERO, false));
      assertEquals(SelectionDraftStateMachine.Phase.PRISM_HEIGHT, machine.phase());
      machine.onEvent(click(0, BlockPos.ZERO, false));
      assertEquals(SelectionDraftStateMachine.Phase.PRISM_BASE, machine.phase());
      assertEquals(0, machine.snapshot().prismBaseCount());
      assertEquals(4, ready.points().size());
      assertEquals(3, ready.prismBaseCount());
   }

   @Test
   void restoreFreezesAndNormalizesAllDraftDataTogether() {
      var mutable = new BlockPos.MutableBlockPos(2, 3, 4);
      var points = new ArrayList<>(List.of(BlockPos.ZERO, mutable));
      var machine = new SelectionDraftStateMachine();
      machine.restore(new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID, points, 0,
         new BlockPos(10, -2, 8), new BlockPos(-3, 12, 1)));
      var restored = machine.snapshot();
      points.clear();
      mutable.set(99, 99, 99);
      assertEquals(List.of(BlockPos.ZERO, SECOND), restored.points());
      assertEquals(new BlockPos(-3, -2, 1), restored.minPoint());
      assertEquals(new BlockPos(10, 12, 8), restored.maxPoint());
      assertThrows(UnsupportedOperationException.class, () -> restored.points().clear());
      machine.clear();
      assertEquals(OperationSelectionMode.CUBOID, machine.snapshot().selectionMode());
      assertEquals(SelectionDraftStateMachine.Phase.CUBOID_EMPTY, machine.phase());
      assertEquals(2, restored.points().size());
   }

   @Test
   void convexHullKeepsItsExistingAltOnlyCuboidBehavior() {
      var machine = new SelectionDraftStateMachine();
      machine.setMode(OperationSelectionMode.CONVEX_HULL);
      var before = machine.snapshot();
      assertEquals(SelectionDraftResult.REJECTED, machine.onEvent(click(0, BlockPos.ZERO, false)));
      assertSame(before, machine.snapshot());
      assertEquals(SelectionDraftResult.UPDATED, machine.onEvent(click(0, BlockPos.ZERO, true)));
      assertEquals(SelectionDraftResult.READY, machine.onEvent(click(1, SECOND, true)));
      assertEquals(OperationSelectionMode.CONVEX_HULL, machine.snapshot().selectionMode());
   }

   private static SelectionDraftEvent click(int button, BlockPos point, boolean alt) {
      return SelectionDraftEvent.fromMouse(button, point, alt);
   }
}
