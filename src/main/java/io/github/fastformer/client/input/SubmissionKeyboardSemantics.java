package io.github.fastformer.client.input;

/** Maps confirmation keys and decides whether the current phase owns the submission. */
public final class SubmissionKeyboardSemantics {
   private static final int KEY_PRESS = 1;
   private static final int KEY_ENTER = 257;
   private static final int KEY_KEYPAD_ENTER = 335;

   private SubmissionKeyboardSemantics() {
   }

   public static Command fromPhysicalKey(int action, int key) {
      return action == KEY_PRESS && (key == KEY_ENTER || key == KEY_KEYPAD_ENTER)
         ? Command.CONFIRM
         : Command.NONE;
   }

   public static Decision decide(
      Command command,
      ClientInputStateMachine.Dispatch submissionRoute,
      boolean canConfirm,
      boolean nearVanillaBlock
   ) {
      if (command == Command.NONE) {
         return Decision.rejected(Rejection.NOT_CONFIRM_KEY);
      }
      if (submissionRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         return Decision.rejected(Rejection.PHASE_BLOCKED);
      }
      if (!canConfirm) {
         return Decision.rejected(Rejection.CANDIDATE_UNCONFIRMED);
      }
      if (nearVanillaBlock) {
         return Decision.rejected(Rejection.VANILLA_TARGET);
      }
      return Decision.acceptedDecision();
   }

   public static boolean requiresCandidateContext(
      Command command, ClientInputStateMachine.Dispatch submissionRoute
   ) {
      return command == Command.CONFIRM
         && submissionRoute != ClientInputStateMachine.Dispatch.BLOCKED;
   }

   public enum Command {
      NONE,
      CONFIRM
   }

   public enum Rejection {
      NONE,
      NOT_CONFIRM_KEY,
      PHASE_BLOCKED,
      CANDIDATE_UNCONFIRMED,
      VANILLA_TARGET
   }

   public record Decision(Rejection rejection) {
      public static Decision acceptedDecision() {
         return new Decision(Rejection.NONE);
      }

      public static Decision rejected(Rejection rejection) {
         return new Decision(rejection);
      }

      public boolean accepted() {
         return rejection == Rejection.NONE;
      }
   }
}
