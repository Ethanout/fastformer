package io.github.fastformer.fastplace.session;

public interface SessionLifecycle {
   default boolean undoStep() {
      return false;
   }

   default boolean canUndoStep() {
      return false;
   }

   default boolean redoStep() {
      return false;
   }

   default boolean canRedoStep() {
      return false;
   }

   default void onSubmodeChanged() {
   }

   default void onModeChanged() {
   }

   default void onStageChanged() {
   }

   default void onDestroyed() {
   }
}
