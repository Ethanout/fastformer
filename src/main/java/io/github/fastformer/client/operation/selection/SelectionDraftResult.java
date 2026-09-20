package io.github.fastformer.client.operation.selection;

/** Draft transitions do not capture world blocks or publish selection parts. */
public enum SelectionDraftResult {
   REJECTED,
   UPDATED,
   READY;

   public boolean handled() {
      return this != REJECTED;
   }
}
