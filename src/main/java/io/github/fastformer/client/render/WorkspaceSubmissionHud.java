package io.github.fastformer.client.render;

import io.github.fastformer.fastplace.FastPlaceActivity;

/**
 * Decides what the HUD shows while a workspace submission waits for the server.
 *
 * <p>The client locks the workspace when it sends a submission, so every build and
 * selection action stops at once. The server publishes its task activity later. A
 * network delay opens a window where the client refuses input and no activity line
 * appears yet. This class keeps the HUD alive in that window and shows the
 * existing submission-pending text instead of action prompts.</p>
 *
 * <p>The lock also makes some key hints false. The cancel key reports the pending
 * state instead of cancelling, and the block key is blocked. This class returns
 * the text and the hint flags that the HUD consumes, so a test drives the real
 * conditions with no client instance.</p>
 */
public final class WorkspaceSubmissionHud {
   /** Reuses the submitted-state text. A new key would duplicate one meaning. */
   public static final String WAITING_KEY = "fastformer.message.operation_submit_pending";
   /** Names the fill mode and the key that changes it. */
   public static final String FILL_MODE_KEY = "fastformer.message.fill_mode";
   /** Names the fill mode only, with no key. */
   public static final String FILL_MODE_STATUS_KEY = "fastformer.message.fill_mode_status";

   private static final int LINE_STEP = 12;
   /** The fill-mode text owns this line. */
   public static final int FILL_LINE_Y = 8;
   /** Quick Replace and the activity task line own this line. */
   public static final int QUICK_REPLACE_OR_TASK_LINE_Y = 20;
   /** The reconnect prompt owns this line. */
   public static final int RECONNECT_LINE_Y = 32;

   private WorkspaceSubmissionHud() {
   }

   /**
    * What one HUD frame draws for a waiting submission.
    *
    * @param waitingKey            text to draw, or null when the frame needs no waiting line
    * @param waitingLineY          vertical position for that text
    * @param suppressActionPrompts true while the locked workspace refuses new actions
    */
   public record Plan(String waitingKey, int waitingLineY, boolean suppressActionPrompts) {
      public boolean showsWaitingLine() {
         return waitingKey != null;
      }
   }

   /**
    * True when any FastFormer element still needs the HUD.
    *
    * <p>A local submission alone keeps the HUD alive. Without this flag a
    * client-only paste submission would draw no waiting line at all.</p>
    */
   public static boolean hudActive(
      boolean reconnectPending,
      boolean buildingEnabled,
      boolean freeScroll,
      boolean geometryActive,
      boolean operationActive,
      boolean activityTask,
      boolean quickReplaceActive,
      boolean submissionPending
   ) {
      return reconnectPending
         || buildingEnabled
         || freeScroll
         || geometryActive
         || operationActive
         || activityTask
         || quickReplaceActive
         || submissionPending;
   }

   public static Plan plan(
      boolean submissionPending,
      boolean activityTask,
      boolean quickReplaceActive,
      boolean reconnectPending
   ) {
      // An activity line already tells the player that work runs. Draw the local
      // waiting line only while no such line exists.
      boolean waiting = submissionPending && !activityTask;
      return new Plan(
         waiting ? WAITING_KEY : null,
         waitingLineY(quickReplaceActive || activityTask, reconnectPending),
         submissionPending
      );
   }

   /**
    * The fill-mode text key for this frame.
    *
    * <p>The default text names the block key. The lock blocks that key, so a
    * pending frame names the fill mode only. The status text keeps the fill
    * information and promises no key.</p>
    */
   public static String fillModeKey(boolean submissionPending) {
      return submissionPending ? FILL_MODE_STATUS_KEY : FILL_MODE_KEY;
   }

   /**
    * True when the activity line shows the cancel key hint.
    *
    * <p>The submission tracker stays pending for the whole submission, including
    * the phase where the server task is active. The cancel key reports the pending
    * state and sends no cancel in that phase, so the hint would be false.</p>
    */
   public static boolean showsCancelHint(FastPlaceActivity activity, boolean submissionPending) {
      return activity.task()
         && activity.cancellable()
         && activity != FastPlaceActivity.RESTORE_TASK
         && !submissionPending;
   }

   /**
    * The first HUD line that no other element owns.
    *
    * <p>The three lines above the waiting text have fixed owners. The waiting text
    * steps past the owners that this frame draws, so it never covers Quick
    * Replace, the activity task line, or the reconnect prompt.</p>
    */
   public static int waitingLineY(boolean quickReplaceOrTask, boolean reconnectPending) {
      if (!quickReplaceOrTask) {
         return QUICK_REPLACE_OR_TASK_LINE_Y;
      }
      return reconnectPending
         ? QUICK_REPLACE_OR_TASK_LINE_Y + 2 * LINE_STEP
         : RECONNECT_LINE_Y;
   }
}
