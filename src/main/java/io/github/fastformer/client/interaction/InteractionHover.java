package io.github.fastformer.client.interaction;

import java.util.List;
import java.util.Objects;
import io.github.fastformer.client.input.OperationInteractionIntent;

/** Visual pointer state. Transitions contain data and cannot edit the selection. */
public final class InteractionHover {
   private Target current;

   public InteractionObject.Id current() {
      return this.current == null ? null : this.current.id();
   }

   public OperationInteractionIntent intent() {
      return this.current == null ? null : this.current.intent();
   }

   public List<Event> update(InteractionObject.Id next) {
      return replace(next == null ? null : new Target(next, null, null));
   }

   public List<Event> updateTarget(InteractionObject object, OperationInteractionIntent intent) {
      return replace(object == null ? null : new Target(object.id(), object, intent));
   }

   private List<Event> replace(Target next) {
      InteractionObject.Id previous = current();
      this.current = next;
      InteractionObject.Id id = current();
      if (Objects.equals(previous, id)) return List.of();
      if (previous == null) return List.of(new Event(id, Phase.ENTER));
      if (id == null) return List.of(new Event(previous, Phase.LEAVE));
      return List.of(new Event(previous, Phase.LEAVE), new Event(id, Phase.ENTER));
   }

   public List<Event> reconcile(SelectionInteractionScene scene) {
      if (this.current == null) return List.of();
      InteractionObject object = scene.object(this.current.id());
      boolean valid = object != null && (this.current.snapshot() == null || this.current.snapshot() == object);
      return valid ? List.of() : update(null);
   }

   private record Target(InteractionObject.Id id, InteractionObject snapshot, OperationInteractionIntent intent) { }

   public enum Phase { ENTER, LEAVE }

   public record Event(InteractionObject.Id object, Phase phase) {
      public Event {
         Objects.requireNonNull(object, "object");
         Objects.requireNonNull(phase, "phase");
      }
   }
}
