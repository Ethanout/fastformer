package io.github.fastformer.client.operation.input;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;

/** Both confirmation shortcuts submit the whole scene. */
public final class SelectionConfirmation {
   private SelectionConfirmation() {}

   public enum Action { SUBMIT_ALL, BLOCKED }

   public static Action decide(ClientOperationWorkspace workspace, boolean sampling) {
      if (workspace.locked() || workspace.editing() || sampling) return Action.BLOCKED;
      return Action.SUBMIT_ALL;
   }
}
