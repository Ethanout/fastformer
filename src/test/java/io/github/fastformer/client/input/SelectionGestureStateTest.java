package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.drag.*;
import io.github.fastformer.client.interaction.SelectionDragCapture;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SelectionGestureStateTest {
   @Test
   void aSessionOwnsOneGestureAndCleanupRemovesIt() {
      var session = new ClientSelectionSession();
      var drag = face(session, 1);
      session.gestures().begin(drag);
      assertSame(drag, session.gestures().face());
      assertNull(session.gestures().gizmo());
      assertThrows(IllegalStateException.class, () -> session.gestures().begin(drag));
      assertFalse(new ClientSelectionSession().gestures().active());
      session.clearLiveInteraction();
      assertFalse(session.gestures().active());
      assertNull(session.gestures().face());
   }

   @Test
   void lateUpdateAndClearCannotReplaceANewerGesture() {
      var session = new ClientSelectionSession();
      var old = face(session, 1);
      var state = session.gestures();
      state.begin(old);
      var moved = old.withSentSteps(2);
      assertTrue(state.update(old, moved));
      assertFalse(state.update(old, old.withSentSteps(3)));
      state.clear(old);
      assertSame(moved, state.face());
      state.clear(moved);
      var replacement = face(session, 2);
      state.begin(replacement);
      assertFalse(state.update(moved, moved.withSentSteps(4)));
      state.clear(moved);
      assertSame(replacement, state.face());
   }

   @Test
   void updateCannotChangeCapturedIdentity() {
      var session = new ClientSelectionSession();
      var first = face(session, 1);
      var different = face(session, 2);
      session.gestures().begin(first);
      assertFalse(session.gestures().update(first, different));
      assertSame(first, session.gestures().face());
   }

   private static WorkspaceFaceDrag face(ClientSelectionSession session, long token) {
      var workspace = session.workspace();
      if (workspace.isEmpty()) {
         workspace.addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
         workspace.beginEdit();
      }
      var part = workspace.part(1).orElseThrow();
      var capture = SelectionDragCapture.create(session.interactionOwnerId(), workspace, List.of(part), 0, token);
      return new WorkspaceFaceDrag(part, 0, true, DragAxisFrame.start(Vec3.ZERO, false),
         new Vec3(1, 0, 0), 0, capture, DeferredDragClick.start(0, 1), null, workspace.activeEditToken());
   }
}
