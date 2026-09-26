package io.github.fastformer.client.input.policy;

/** Pure policy for the FastFormer cancel key. */
public final class CancelInputSemantics {
   public static final int PRESS = 1;
   public static final int CANCEL_KEY = 81;
   public static final int ESCAPE_KEY = 256;

   private CancelInputSemantics() {
   }

   public static Decision decide(
      int action,
      int key,
      boolean clientReady,
      boolean quitChannelAvailable,
      boolean sessionCancellable,
      boolean submissionPending,
      boolean operationRestorePending,
      boolean previewRestorePending
   ) {
      if (action != PRESS || key != CANCEL_KEY || !clientReady || !quitChannelAvailable || !sessionCancellable) {
         return Decision.IGNORED;
      }
      if (submissionPending) {
         return new Decision(Command.REPORT_SUBMISSION_PENDING, false, false);
      }
      return new Decision(Command.REQUEST_CANCEL, operationRestorePending, previewRestorePending);
   }

   /**
    * Escape always keeps vanilla screen behavior and never cancels a session.
    */
   public static Decision decideEscape(
      boolean clientReady,
      boolean quitChannelAvailable,
      boolean sessionCancellable,
      boolean submissionPending,
      boolean operationRestorePending,
      boolean previewRestorePending
   ) {
      return Decision.IGNORED;
   }

   /**
    * The vanilla drop key is bound to the cancel key by default, and the
    * keyboard handler queues that vanilla click before this handler runs. An
    * captured session cancel must remove the queued click, or the same physical
    * press also drops the held item. A rebound drop key keeps its own meaning.
    */
   public static boolean capturesVanillaDrop(
      Decision decision,
      boolean cancelKeyBoundToVanillaDrop
   ) {
      return decision.command() != Command.IGNORE
         && decision.command() != Command.REPORT_SUBMISSION_PENDING
         && cancelKeyBoundToVanillaDrop;
   }

   public enum Command {
      IGNORE,
      REPORT_SUBMISSION_PENDING,
      REQUEST_CANCEL
   }

   public record Decision(
      Command command,
      boolean dismissOperationRestore,
      boolean dismissPreviewRestore
   ) {
      private static final Decision IGNORED = new Decision(Command.IGNORE, false, false);

      public Decision {
         if (command == null) {
            throw new IllegalArgumentException("command must not be null");
         }
      }
   }
}
