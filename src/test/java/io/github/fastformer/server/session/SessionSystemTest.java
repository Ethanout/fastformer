package io.github.fastformer.server.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.quickshape.QuickShapeDraft;
import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.fastplace.session.OperationSession;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SessionSystemTest {
   @Test
   void dimensionChangeParksAndRestoresTheSameDraft() {
      try (var runtime = new SessionSystem()) {
         var shape = runtime.quickShape;
         UUID owner = UUID.randomUUID();
         var draft = draft(new BlockPos(4, 5, 6));
         shape.sessions.activate(owner, draft);
         shape.parkDimensionSession(owner, Level.OVERWORLD);
         assertFalse(shape.sessions.hasActive(owner));
         assertSame(draft, shape.sessions.parked(owner, Level.OVERWORLD));
         shape.restoreDimensionSession(owner, Level.NETHER);
         assertFalse(shape.sessions.hasActive(owner));
         shape.restoreDimensionSession(owner, Level.OVERWORLD);
         assertSame(draft, shape.sessions.active(owner));
         assertNull(shape.sessions.parked(owner, Level.OVERWORLD));
      }
   }

   @Test
   void eachDimensionKeepsItsOwnDraft() {
      try (var runtime = new SessionSystem()) {
         var shape = runtime.quickShape;
         UUID owner = UUID.randomUUID();
         var overworld = draft(new BlockPos(1, 0, 0));
         var nether = draft(new BlockPos(0, 0, 1));
         shape.sessions.activate(owner, overworld);
         shape.parkDimensionSession(owner, Level.OVERWORLD);
         shape.sessions.activate(owner, nether);
         assertSame(nether, shape.sessions.active(owner));
         assertSame(overworld, shape.sessions.parked(owner, Level.OVERWORLD));
         shape.parkDimensionSession(owner, Level.NETHER);
         shape.restoreDimensionSession(owner, Level.OVERWORLD);
         assertSame(overworld, shape.sessions.active(owner));
         assertSame(nether, shape.sessions.parked(owner, Level.NETHER));
      }
   }

   @Test
   void restoringUnknownDimensionDoesNotReplaceLiveDraft() {
      try (var runtime = new SessionSystem()) {
         UUID owner = UUID.randomUUID();
         var draft = draft(new BlockPos(9, 9, 9));
         runtime.quickShape.sessions.activate(owner, draft);
         runtime.quickShape.restoreDimensionSession(owner, Level.OVERWORLD);
         assertSame(draft, runtime.quickShape.sessions.active(owner));
      }
   }

   @Test
   void parkingUnknownDimensionPreservesDraft() {
      try (var runtime = new SessionSystem()) {
         UUID owner = UUID.randomUUID();
         var draft = draft(BlockPos.ZERO);
         runtime.quickShape.sessions.activate(owner, draft);
         runtime.quickShape.parkDimensionSession(owner, null);
         assertSame(draft, runtime.quickShape.sessions.active(owner));
      }
   }

   @Test
   void closingOneRuntimeCannotAffectSameOwnerOnAnother() {
      try (var first = new SessionSystem(); var second = new SessionSystem()) {
         UUID owner = UUID.randomUUID();
         var draft = draft(BlockPos.ZERO);
         var task = PlacementTask.ready(Set.of(BlockPos.ZERO), new PlacementTaskPlan(null, null,
            OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD));
         first.quickShape.sessions.activate(owner, draft(BlockPos.ZERO));
         first.operation.sessions.activate(owner, new OperationSession(OperationSelectionMode.CUBOID, 10));
         first.geometry.sessions.activate(owner, new GeometrySession());
         second.quickShape.sessions.activate(owner, draft);
         second.quickShape.tasks.put(owner, task);
         first.close();
         assertTrue(first.closed());
         assertFalse(first.quickShape.sessions.hasActive(owner));
         assertFalse(first.operation.sessions.hasActive(owner));
         assertFalse(first.geometry.sessions.hasActive(owner));
         assertSame(draft, second.quickShape.sessions.active(owner));
         assertTrue(second.quickShape.taskActive(owner));
      }
   }

   private static QuickShapeDraft draft(BlockPos point) {
      var draft = new QuickShapeDraft();
      draft.addPoint(point, Vec3.ZERO, new Vec3(0, 0, 1));
      return draft;
   }
}
