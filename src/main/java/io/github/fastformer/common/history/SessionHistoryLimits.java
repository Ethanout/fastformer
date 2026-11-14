package io.github.fastformer.common.history;

/** Domain limits shared by editable session histories, settings and command validation. */
public final class SessionHistoryLimits {
   public static final int DEFAULT_SESSION_UNDO_HISTORY_LIMIT = 100;
   public static final int MAX_UNDO_HISTORY_LIMIT = 800;

   private SessionHistoryLimits() {}
}
