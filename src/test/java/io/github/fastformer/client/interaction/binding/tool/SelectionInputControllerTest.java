package io.github.fastformer.client.interaction.binding.tool;

import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.binding.context.SelectionBindingContext;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectionInputControllerTest {
   @Test void unownedPhysicalInputDoesNotFreezeOrDispatch() {
      var host = new Owner();
      host.owns = false;
      assertFalse(SelectionInputController.captureBindings(host, 1, 0, 10L).owns());
      assertEquals(1, host.synchronizations);
      assertEquals(0, host.freezes);
      assertEquals(0, host.signals);
   }

   @Test void ownedPressFreezesOnceAndRecordsTheResourceDecision() {
      var host = new Owner();
      var result = SelectionInputController.captureBindings(host, 1, 0, 10L);
      assertTrue(result.owns());
      assertTrue(result.consumesVanilla());
      assertEquals(1, host.freezes);
      assertEquals(1, host.signals);
      assertEquals(result, host.pressed);
      assertEquals(0, host.releases);
   }

   @Test void physicalReleaseSettlesEvenIfTheResourceActionThrows() {
      var host = new Owner();
      host.failSignal = true;
      assertThrows(IllegalStateException.class, () -> SelectionInputController.captureBindings(host, 0, 5, 42L));
      assertEquals(1, host.releases);
      assertEquals(5, host.releasedButton);
      assertEquals(42L, host.releasedAt);
      assertFalse(host.releaseHandled);
   }

   @Test void vanillaInteractionUsesTheAlreadyCapturedPhysicalDecision() {
      var host = new Owner();
      host.cached = new SelectionBindingInput.Result(true, false);
      assertSame(host.cached, SelectionInputController.interactionBindings(host, 1));
      assertEquals(0, host.freezes);
      assertEquals(0, host.signals);
   }

   @Test void failedReleaseFreezeStillSettlesTheOwnedPhysicalButton() {
      var host = new Owner();
      host.failFreeze = true;
      assertThrows(IllegalStateException.class, () -> SelectionInputController.captureBindings(host, 0, 4, 99L));
      assertEquals(1, host.releases);
      assertEquals(4, host.releasedButton);
      assertEquals(99L, host.releasedAt);
      assertFalse(host.releaseHandled);
   }

   @Test void failedPressFreezeDoesNotInventARelease() {
      var host = new Owner();
      host.failFreeze = true;
      assertThrows(IllegalStateException.class, () -> SelectionInputController.captureBindings(host, 1, 4, 99L));
      assertEquals(0, host.releases);
   }

   @Test void releaseReportsWhetherItsActionAlreadySettledTheGesture() {
      var host = new Owner();
      host.actionHandledRelease = true;
      SelectionInputController.captureBindings(host, 0, 1, 24L);
      assertEquals(1, host.releases);
      assertTrue(host.releaseHandled);
   }

   private static final class Owner implements SelectionInputController.Host {
      boolean owns = true, failSignal, failFreeze, actionHandledRelease, releaseHandled;
      int synchronizations, freezes, signals, releases, releasedButton;
      long releasedAt;
      SelectionBindingInput.Result cached, pressed;
      public void synchronize() { synchronizations++; }
      public boolean ownsPointer(int button) { return owns; }
      public boolean allowsInteraction() { return true; }
      public SelectionBindingInput.Result interaction(int button) { return cached; }
      public SelectionInputController.FrozenPress freeze(int button, long time, boolean press) {
         freezes++;
         if (failFreeze) throw new IllegalStateException("sampling failed");
         return new SelectionInputController.FrozenPress(
            new SelectionBindingContext("fastformer:cuboid", "fastformer:cuboid", EnumSet.noneOf(SelectionBindingContext.Fact.class)),
            new BindingCommands() {
               public void replayPress(long time) { throw new AssertionError("Selection cannot replay input"); }
            }, () -> actionHandledRelease);
      }
      public BindingAction.Result signal(String name, String gesture, BindingContext context, BindingCommands commands) {
         signals++;
         if (failSignal) throw new IllegalStateException("resource action failed");
         return BindingAction.Result.HANDLED;
      }
      public void pressed(int button, SelectionBindingInput.Result result) { pressed = result; }
      public void finishRelease(int button, long time, boolean handled) {
         releases++; releasedButton = button; releasedAt = time; releaseHandled = handled;
      }
   }
}
