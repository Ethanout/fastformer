package io.github.fastformer.client.mode;

import io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.input.state.ClientSemanticEvent;
import java.util.Objects;
import java.util.Set;

/** Input ownership and submission admission declared by one mode phase. */
public record ModeInputPolicy(
   Dispatch regular, Dispatch submit, Dispatch createSelection, Dispatch pasteWorkspace, Dispatch cancel,
   boolean placementSubmission, boolean workspaceSubmission, boolean blocksDraftLoad, Set<InputKind> dataDrivenInputs
) {
   public ModeInputPolicy(Dispatch regular, Dispatch submit, Dispatch createSelection, Dispatch pasteWorkspace, Dispatch cancel,
      boolean placementSubmission, boolean workspaceSubmission, boolean blocksDraftLoad) {
      this(regular, submit, createSelection, pasteWorkspace, cancel, placementSubmission, workspaceSubmission, blocksDraftLoad, Set.of());
   }

   public ModeInputPolicy {
      Objects.requireNonNull(regular, "regular");
      Objects.requireNonNull(submit, "submit");
      Objects.requireNonNull(createSelection, "createSelection");
      Objects.requireNonNull(pasteWorkspace, "pasteWorkspace");
      Objects.requireNonNull(cancel, "cancel");
      dataDrivenInputs = Set.copyOf(dataDrivenInputs);
      if (!Set.of(InputKind.POINTER, InputKind.INTERACTION, InputKind.SCROLL).containsAll(dataDrivenInputs))
         throw new IllegalArgumentException("Only pointer, interaction and scroll rules can delegate to binding data");
   }

   public Dispatch dispatch(InputKind input) {
      if (dataDrivenInputs.contains(Objects.requireNonNull(input, "input"))) return Dispatch.VANILLA;
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
