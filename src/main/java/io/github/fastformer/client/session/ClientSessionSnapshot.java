package io.github.fastformer.client.session;

/** A read-only view of the client features that can own the current session. */
public record ClientSessionSnapshot(
   boolean quickShapeActive,
   boolean specialShapeActive,
   boolean specialItemActive
) {
   public boolean anyActive() {
      return quickShapeActive || specialShapeActive || specialItemActive;
   }
}
