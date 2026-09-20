package io.github.fastformer.client.input;

/**
 * Maps physical workspace shortcuts to commands and reports who keeps the key
 * press.
 *
 * <p>Undo is the one command with two owners. The design gives the client
 * workspace the first turn and the world request the second turn, so this type
 * decides the local step and names the owner of the press:</p>
 *
 * <ul>
 *   <li>{@link Decision#accepted()} - the workspace runs the command.</li>
 *   <li>{@link Decision#handsPressToWorldUndo()} - the workspace declines and
 *       the world undo request later in the key handler owns the press.</li>
 *   <li>{@link Decision#consumesPress()} without an accepted command - a busy
 *       state holds the press. The world undo request must not run.</li>
 *   <li>Every other rejection leaves the press alone, exactly as before.</li>
 * </ul>
 *
 * <p>A {@link ClientInputStateMachine.Dispatch#BLOCKED} key route returns from
 * the key handler before this type, so the blocked case is a guard rather than
 * an ordinary path.</p>
 */
public final class WorkspaceKeyboardSemantics {
   private static final int KEY_PRESS = 1;
   private static final int KEY_A = 65;
   private static final int KEY_C = 67;
   private static final int KEY_V = 86;
   private static final int KEY_Z = 90;
   private static final int KEY_BACKSPACE = 259;
   private static final int KEY_DELETE = 261;

   private WorkspaceKeyboardSemantics() {
   }

   public static Command fromPhysicalKey(int action, int key, boolean controlDown) {
      if (action != KEY_PRESS) {
         return Command.NONE;
      }
      if (controlDown) {
         return switch (key) {
            case KEY_A -> Command.SELECT_ALL;
            case KEY_C -> Command.COPY;
            case KEY_V -> Command.PASTE;
            case KEY_Z -> Command.UNDO;
            case KEY_BACKSPACE, KEY_DELETE -> Command.REMOVE_SELECTED;
            default -> Command.NONE;
         };
      }
      return key == KEY_BACKSPACE || key == KEY_DELETE
         ? Command.MARK_SELECTED_FOR_DELETION
         : Command.NONE;
   }

   public static Decision decide(
      Command command,
      ClientInputStateMachine.Dispatch keyRoute,
      ClientInputStateMachine.Dispatch pasteRoute,
      WorkspaceFacts facts
   ) {
      WorkspaceFacts supplied = facts == null ? WorkspaceFacts.inactive() : facts;
      if (command == Command.NONE) {
         return Decision.ignored();
      }
      if (command == Command.UNDO) {
         return decideUndo(keyRoute, supplied);
      }
      if (command == Command.PASTE) {
         return pasteRoute == ClientInputStateMachine.Dispatch.BLOCKED
            ? Decision.rejected(Rejection.PHASE_BLOCKED)
            : Decision.accepted(command);
      }
      if (keyRoute != ClientInputStateMachine.Dispatch.OPERATION) {
         return Decision.rejected(Rejection.PHASE_BLOCKED);
      }
      return supplied.workspaceActive()
         ? Decision.accepted(command)
         : Decision.rejected(Rejection.WORKSPACE_INACTIVE);
   }

   /**
    * Selects the owner of one Ctrl+Z press.
    *
    * <p>The order matters. A busy workspace is checked first, because a locked
    * or editing workspace has no local step yet must not reach the world
    * request. The key route then gives the local duty. Only a route that another
    * session holds hands the press to the world request.</p>
    */
   static Decision decideUndo(
      ClientInputStateMachine.Dispatch keyRoute,
      WorkspaceFacts facts
   ) {
      if (keyRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         return Decision.rejected(Rejection.BLOCKED_INPUT_PHASE);
      }
      if (facts.submissionPending()) {
         return Decision.rejected(Rejection.SUBMISSION_PENDING);
      }
      if (facts.editInProgress()) {
         return Decision.rejected(Rejection.EDIT_IN_PROGRESS);
      }
      if (!ownsLocalUndoDuty(keyRoute)) {
         return Decision.rejected(Rejection.OTHER_SESSION_ACTIVE);
      }
      return facts.localUndoHistory()
         ? Decision.accepted(Command.UNDO)
         : Decision.rejected(Rejection.NO_LOCAL_HISTORY);
   }

   /**
    * True when no other session holds the key.
    *
    * <p>{@code OPERATION} is an operation session. {@code VANILLA} is the idle
    * phase, where the input machine reports that no session is present. The
    * operation route covers a workspace that shows parts. The idle route covers
    * a workspace whose parts are gone but whose undo chain continues, which is
    * the state after the last part is removed. A building or geometry route
    * keeps its own undo, so a stale local chain never takes it.</p>
    */
   private static boolean ownsLocalUndoDuty(ClientInputStateMachine.Dispatch keyRoute) {
      return keyRoute == ClientInputStateMachine.Dispatch.OPERATION
         || keyRoute == ClientInputStateMachine.Dispatch.VANILLA;
   }

   /**
    * The workspace facts that one decision reads.
    *
    * <p>{@code workspaceActive} reports visible content. {@code localUndoDepth}
    * reports the actual undo chain, which survives the removal of the last part.
    * The two are separate because the workspace content and the undo duty are
    * separate states.</p>
    */
   public record WorkspaceFacts(
      boolean workspaceActive,
      int localUndoDepth,
      boolean editInProgress,
      boolean submissionPending
   ) {
      public WorkspaceFacts {
         if (localUndoDepth < 0) {
            throw new IllegalArgumentException("An undo depth cannot be negative");
         }
      }

      public static WorkspaceFacts inactive() {
         return new WorkspaceFacts(false, 0, false, false);
      }

      public boolean localUndoHistory() {
         return this.localUndoDepth > 0;
      }
   }

   public enum Command {
      NONE,
      COPY,
      PASTE,
      SELECT_ALL,
      MARK_SELECTED_FOR_DELETION,
      REMOVE_SELECTED,
      UNDO
   }

   public enum Rejection {
      NONE,
      /** Another input phase owns the key; the press is left alone. */
      PHASE_BLOCKED,
      WORKSPACE_INACTIVE,
      /** No local undo step exists; the world request owns the press. */
      NO_LOCAL_HISTORY,
      /** Building or geometry holds the session; the world request owns the press. */
      OTHER_SESSION_ACTIVE,
      /** The input machine is busy; the press is consumed and no world request runs. */
      BLOCKED_INPUT_PHASE,
      /** A transform gesture holds the workspace; the press is consumed. */
      EDIT_IN_PROGRESS,
      /** A submission holds the workspace; the press is consumed and reported. */
      SUBMISSION_PENDING
   }

   public record Decision(Command command, Rejection rejection) {
      public static Decision ignored() {
         return new Decision(Command.NONE, Rejection.NONE);
      }

      public static Decision accepted(Command command) {
         return new Decision(command, Rejection.NONE);
      }

      public static Decision rejected(Rejection rejection) {
         return new Decision(Command.NONE, rejection);
      }

      public boolean accepted() {
         return command != Command.NONE && rejection == Rejection.NONE;
      }

      /**
       * True when the world undo request must receive the press, because the
       * local workspace has no step to run. A busy rejection never returns true.
       */
      public boolean handsPressToWorldUndo() {
         return rejection == Rejection.NO_LOCAL_HISTORY
            || rejection == Rejection.OTHER_SESSION_ACTIVE;
      }

      /**
       * True when no other handler may see the press: either the workspace ran
       * the command, or a busy state owns it.
       */
      public boolean consumesPress() {
         return accepted()
            || rejection == Rejection.BLOCKED_INPUT_PHASE
            || rejection == Rejection.EDIT_IN_PROGRESS
            || rejection == Rejection.SUBMISSION_PENDING;
      }
   }
}
