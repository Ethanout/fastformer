package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SelectionPointerReleaseTest {
   @Test
   void releaseUsesSuppliedSessionWithoutClearingAnotherSessionsCapture() {
      var first = new ClientInputSession();
      var second = new ClientInputSession();
      first.operationClickCapturedButton = 0;
      second.operationClickCapturedButton = 0;
      assertTrue(MouseReleaseDispatcher.finish(null, first, MouseButtonInputSemantics.RELEASE, 0, 123L));
      assertEquals(-1, first.operationClickCapturedButton);
      assertEquals(0, second.operationClickCapturedButton);
      assertTrue(MouseReleaseDispatcher.finish(null, second, MouseButtonInputSemantics.RELEASE, 0, 124L));
      assertEquals(-1, second.operationClickCapturedButton);
   }

   @AfterEach
   void clearPointer() throws Exception {
      FastPlaceClientInput.inputSession().reset();
   }

   @Test
   void sharedReleaseConsumesOnlyTheMatchingOperationCapture() throws Exception {
      var session = FastPlaceClientInput.inputSession();
      session.reset();
      for (int button : new int[] {0, 1}) {
         session.operationClickCapturedButton = button;
         assertFalse(releaseMouse(1 - button));
         assertEquals(button, session.operationClickCapturedButton);
         assertTrue(releaseMouse(button));
         assertEquals(-1, session.operationClickCapturedButton);
         assertFalse(releaseMouse(button));
      }
   }

   @Test
   void rightReleasePreservesLeftGeometryCapture() throws Exception {
      var session = FastPlaceClientInput.inputSession();
      session.reset();
      session.geometryClickCapturedButton = 0;
      assertFalse(releaseMouse(1));
      assertEquals(0, session.geometryClickCapturedButton);
      assertTrue(releaseMouse(0));
      assertEquals(-1, session.geometryClickCapturedButton);
   }

   private static boolean releaseMouse(int button) throws Exception {
      return MouseReleaseDispatcher.finish(null, FastPlaceClientInput.inputSession(),
         MouseButtonInputSemantics.RELEASE, button, System.nanoTime());
   }

   @Test
   void undoReleaseUsesCapturedTimeInsteadOfDispatchTime() throws Exception {
      var session = FastPlaceClientInput.inputSession();
      session.reset();
      session.undoPress.press(System.nanoTime());
      session.undoPressCaptured = true;
      long releasedAt = System.nanoTime() + 1_000_000_000L;
      assertTrue(MouseReleaseDispatcher.finish(null, session, MouseButtonInputSemantics.RELEASE, 0, releasedAt));
      assertFalse(session.undoPressCaptured);
      assertFalse(session.undoPress.release(releasedAt, Long.MAX_VALUE));
   }

   @Test
   void staleCapturedReleasePreservesBothCurrentTokenAndPointer() throws Exception {
      var pointer = pointer();
      long previous = pointer.begin(PointerGestureState.Kind.WORKSPACE_FACE);
      long current = pointer.begin(PointerGestureState.Kind.WORKSPACE_GIZMO);
      FastPlaceClientInput.inputSession().pointerGestureToken = current;
      release(previous);
      assertTrue(pointer.owns(current, PointerGestureState.Kind.WORKSPACE_GIZMO));
      assertEquals(current, FastPlaceClientInput.inputSession().pointerGestureToken);
      release(current);
      assertEquals(0L, pointer.activeToken());
      assertEquals(0L, FastPlaceClientInput.inputSession().pointerGestureToken);
   }

   @Test
   void emptySelectionReleaseCannotFinishAnUnrelatedGesture() throws Exception {
      var pointer = pointer();
      long current = pointer.begin(PointerGestureState.Kind.OPERATION_POINT);
      FastPlaceClientInput.inputSession().pointerGestureToken = current;
      invoke("finishWorkspaceFaceDrag");
      invoke("finishWorkspaceGizmoDrag");
      assertTrue(pointer.owns(current, PointerGestureState.Kind.OPERATION_POINT));
      assertEquals(current, FastPlaceClientInput.inputSession().pointerGestureToken);
   }

   private static void release(long token) throws Exception {
      Method method = FastPlaceClientInput.class.getDeclaredMethod("finishPointerGesture", long.class);
      method.setAccessible(true);
      method.invoke(null, token);
   }

   private static void invoke(String name) throws Exception {
      Method method = FastPlaceClientInput.class.getDeclaredMethod(name);
      method.setAccessible(true);
      method.invoke(null);
   }

   private static PointerGestureState pointer() throws Exception {
      return FastPlaceClientInput.inputSession().pointerGesture;
   }
}
