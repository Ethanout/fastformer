package io.github.fastformer.client.mode;

import io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.input.state.ClientSemanticEvent;
import java.util.Objects;

/** Input ownership and submission admission declared by one mode phase. */
public record ModeInputPolicy(
   Dispatch regular, Dispatch submit, Dispatch createSelection, Dispatch pasteWorkspace, Dispatch cancel,
   boolean placementSubmission, boolean workspaceSubmission, boolean blocksDraftLoad
) {
   public ModeInputPolicy {
      Objects.requireNonNull(regular, "regular");
      Objects.requireNonNull(submit, "submit");
      Objects.requireNonNull(createSelection, "createSelection");
      Objects.requireNonNull(pasteWorkspace, "pasteWorkspace");
      Objects.requireNonNull(cancel, "cancel");
   }

   public Dispatch dispatch(InputKind input) {
      return switch (Objects.requireNonNull(input, "input")) {
         case KEY, POINTER, INTERACTION, SCROLL -> regular;
         case SUBMIT -> submit;
         case CREATE_SELECTION -> createSelection;
         case PASTE_WORKSPACE -> pasteWorkspace;
         case CANCEL -> cancel;
      };
   }

   public boolean accepts(ClientSemanticEvent.Submit request) {
      return switch (Objects.requireNonNull(request, "request")) {
         case ClientSemanticEvent.Submit.Placement ignored -> placementSubmission;
         case ClientSemanticEvent.Submit.Workspace ignored -> workspaceSubmission;
      };
   }
}
