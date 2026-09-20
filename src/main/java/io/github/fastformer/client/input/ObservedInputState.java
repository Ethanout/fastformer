package io.github.fastformer.client.input;

import java.util.Objects;

/**
 * Maps the observed session flags to the input state.
 *
 * <p>This type holds the order of the observation only, so a test can drive the
 * real order without a live client. {@code FastPlaceClientInput} collects the
 * flags from the live sources and calls {@link #stateFor(Sessions)}.</p>
 *
 * <p>The order gives the states their priority. A recovery task outranks a
 * placement task. A placement task outranks an operation session. An operation
 * session with workspace parts or with a confirmed selection is an adjustment,
 * and an operation session without either is a selection. Geometry and building
 * follow. The idle state is the result when no flag is set.</p>
 */
public final class ObservedInputState {
   private ObservedInputState() {
   }

   public static ClientInputStateMachine.State stateFor(Sessions sessions) {
      Objects.requireNonNull(sessions, "sessions");
      if (sessions.restoringTask()) {
         return ClientInputStateMachine.State.RESTORING;
      }
      if (sessions.placementTask()) {
         return ClientInputStateMachine.State.PLACING;
      }
      if (sessions.operationSession()) {
         return sessions.workspaceHasParts() || sessions.selectionConfirmed()
            ? ClientInputStateMachine.State.ADJUSTING
            : ClientInputStateMachine.State.SELECTING;
      }
      if (sessions.geometrySession()) {
         return ClientInputStateMachine.State.GEOMETRY;
      }
      return sessions.buildingSession()
         ? ClientInputStateMachine.State.BUILDING
         : ClientInputStateMachine.State.IDLE;
   }

   /**
    * The session flags that one observation reads.
    *
    * <p>{@code operationSession} is the composed operation flag of the preview
    * core. It is true for a preview operation, for visible workspace parts, and
    * for a live server selection. {@code workspaceHasParts} and
    * {@code selectionConfirmed} split the adjustment test inside that flag.</p>
    */
   public record Sessions(
      boolean restoringTask,
      boolean placementTask,
      boolean operationSession,
      boolean workspaceHasParts,
      boolean selectionConfirmed,
      boolean geometrySession,
      boolean buildingSession
   ) {
      /** The flags of a client that shows nothing. */
      public static Sessions idle() {
         return new Sessions(false, false, false, false, false, false, false);
      }
   }
}
