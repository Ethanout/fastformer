package io.github.fastformer.client.quickshape.input;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot;
import net.minecraft.world.phys.Vec3;

/** Physical capture and dispatched capture advance independently within a tick. */
public final class QuickShapeUndoGesture {
   private static final long SHORT_PRESS_NANOS = 250_000_000L;
   public sealed interface Event {
      record Press(QuickShapeSubmissionSnapshot draft, long occurredAtNanos, Vec3 eye, Vec3 view) implements Event { }
      record Release(Press press, long occurredAtNanos) implements Event { }
   }

   private Event.Press physicalPress;
   private Event.Press activePress;

   public boolean captured() { return physicalPress != null || activePress != null; }

   public Event.Press press(QuickShapeSubmissionSnapshot draft, long time, Vec3 eye, Vec3 view) {
      if (physicalPress != null) return null;
      physicalPress = new Event.Press(draft, time, eye, view);
      return physicalPress;
   }

   public Event.Release release(int button, long time) {
      if (button != 0 || physicalPress == null) return null;
      var release = new Event.Release(physicalPress, time);
      physicalPress = null;
      return release;
   }

   public Event.Press dispatch(Event event, ClientInputStateMachine routing, QuickShapeSubmissionSnapshot current) {
      if (event instanceof Event.Press press) {
         activePress = matches(press, routing, current) ? press : null;
         return null;
      }
      var release = (Event.Release) event;
      if (activePress != release.press()) return null;
      var finished = activePress;
      activePress = null;
      long duration = release.occurredAtNanos() - finished.occurredAtNanos();
      return duration >= 0 && duration <= SHORT_PRESS_NANOS && matches(finished, routing, current) ? finished : null;
   }

   private boolean matches(Event.Press press, ClientInputStateMachine routing, QuickShapeSubmissionSnapshot current) {
      return routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.BUILDING
         && press.draft().equals(current);
   }

   public void cancel() {
      physicalPress = null;
      activePress = null;
   }
}
