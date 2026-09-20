package io.github.fastformer.client.interaction;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.client.input.OperationInteractionIntent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class InteractionHoverTest {
   @Test
   void switchingLeavesOldObjectBeforeEnteringNewObject() {
      var hover = new InteractionHover();
      var first = new InteractionObject.Id(UUID.randomUUID(), "label", 1);
      var second = new InteractionObject.Id(first.session(), "label", 2);
      assertEquals(List.of(new InteractionHover.Event(first, InteractionHover.Phase.ENTER)), hover.update(first));
      assertTrue(hover.update(first).isEmpty());
      assertEquals(List.of(new InteractionHover.Event(first, InteractionHover.Phase.LEAVE),
         new InteractionHover.Event(second, InteractionHover.Phase.ENTER)), hover.update(second));
      assertEquals(List.of(new InteractionHover.Event(second, InteractionHover.Phase.LEAVE)), hover.update(null));
      assertTrue(hover.update(null).isEmpty());
   }

   @Test
   void deletionAndNumberReuseCannotTransferHover() {
      var session = session();
      session.updateHover(1);
      var old = session.hoveredObject();
      assertNotNull(old);
      session.workspace().removeSelectedParts();
      session.workspace().addParts(List.of(part()));
      session.publishInteractionScene();
      assertNull(session.hoveredObject());
      session.updateHover(1);
      assertNotEquals(old, session.hoveredObject());
   }

   @Test
   void geometryReplacementClearsOldHitUntilTheNextPointerSample() {
      var session = session();
      session.updateHover(1);
      var original = session.hoveredObject();
      session.workspace().beginEdit();
      session.workspace().updatePart(session.workspace().part(1).orElseThrow().withTranslation(new BlockPos(5, 0, 0)));
      session.workspace().finishEdit();
      session.publishInteractionScene();
      assertNull(session.hoveredObject());
      assertNull(session.hoveredIntent());
      session.updateHover(1);
      assertEquals(original, session.hoveredObject());
      session.clearLiveInteraction();
      assertNull(session.hoveredObject());
   }

   @Test
   void submissionLockAndUnknownTargetsClearHoverWithoutChangingSelection() {
      var session = session();
      var selection = session.workspace().selectedIds();
      session.updateHover(1);
      session.updateHover(99);
      assertNull(session.hoveredObject());
      session.updateHover(1);
      session.workspace().setLocked(true);
      session.publishInteractionScene();
      assertNull(session.hoveredObject());
      assertTrue(session.updateHover(1).isEmpty());
      assertEquals(selection, session.workspace().selectedIds());
   }

   private static ClientSelectionSession session() {
      var session = new ClientSelectionSession();
      session.workspace().addParts(List.of(part()));
      session.publishInteractionScene();
      return session;
   }

   @Test
   void faceAndGizmoUseTheirOwnObjectsButSharePartHighlight() {
      var session = session();
      var part = session.interactionScene().parts().get(1);
      session.updateHover(new OperationInteractionIntent.Part(1, 1, OperationInteractionIntent.PartSurface.FRAME));
      assertEquals(part.frame().id(), session.hoveredObject());
      var face = new OperationInteractionIntent.Face(1, part.bounds(), null, true);
      session.updateHover(face);
      assertEquals(part.frame().id(), session.hoveredObject());
      assertSame(face, session.hoveredIntent());
      assertEquals(1, session.hoveredPartId());
      var gizmo = new OperationInteractionIntent.Gizmo(1, false, null, null);
      var transitions = session.updateHover(gizmo);
      assertEquals(List.of(new InteractionHover.Event(part.frame().id(), InteractionHover.Phase.LEAVE),
         new InteractionHover.Event(part.gizmo().id(), InteractionHover.Phase.ENTER)), transitions);
      assertSame(gizmo, session.hoveredIntent());
      assertEquals(1, session.hoveredPartId());
   }

   @Test
   void hidingLockedGizmoClearsHoverAndRejectsTheOldTarget() {
      var session = new ClientSelectionSession();
      session.workspace().addParts(List.of(part().withTranslation(new BlockPos(5, 0, 0))));
      session.publishInteractionScene();
      var intent = new OperationInteractionIntent.Gizmo(1, false, null, null);
      session.updateHover(intent);
      var object = session.hoveredObject();
      assertNotNull(object);

      session.workspace().toggleSelected(1);
      session.publishInteractionScene();
      assertNull(session.hoveredObject());
      assertNull(session.hoveredIntent());
      assertTrue(session.updateHover(intent).isEmpty());
      assertNull(session.hoveredObject());
      session.updateHover(1);
      assertNotNull(session.hoveredObject());

      session.workspace().selectOnly(1);
      session.publishInteractionScene();
      session.updateHover(intent);
      assertEquals(object, session.hoveredObject());
   }

   @Test
   void commonGizmoHoverDoesNotHighlightAPartAndDisappearsWithTheGroup() {
      var session = session();
      session.workspace().addParts(List.of(part()));
      session.workspace().selectAll();
      session.publishInteractionScene();
      session.updateHover(new OperationInteractionIntent.Gizmo(0, true, null, null));
      assertEquals(session.interactionScene().groupGizmo().id(), session.hoveredObject());
      assertEquals(0, session.hoveredPartId());
      session.workspace().selectOnly(1);
      session.publishInteractionScene();
      assertNull(session.hoveredObject());
      assertNull(session.hoveredIntent());
   }

   @Test
   void repeatedHitOnTheSameObjectUpdatesDetailsWithoutEnterLeave() {
      var session = session();
      var first = new OperationInteractionIntent.Part(1, 2);
      var next = new OperationInteractionIntent.Part(1, 4);
      session.updateHover(first);
      assertTrue(session.updateHover(next).isEmpty());
      assertSame(next, session.hoveredIntent());
      session.workspace().setLocked(true);
      session.publishInteractionScene();
      assertNull(session.hoveredIntent());
      assertEquals(0, session.hoveredPartId());
   }

   private static ClientSelectionPart part() {
      return new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
         OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO),
         Map.of(), WorkspaceTransform.IDENTITY, false);
   }
}
