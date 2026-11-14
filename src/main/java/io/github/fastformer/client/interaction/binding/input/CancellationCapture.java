package io.github.fastformer.client.interaction.binding.input;

import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.CancellationCommand;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.binding.context.key.CancellationKeyContext;
import java.util.Optional;
import java.util.function.BiFunction;

/** Resolves capture data without cancelling a session before earlier queued input runs. */
public record CancellationCapture(BindingAction.Result result, CancellationCommand command,
   boolean dismissOperationRestore, boolean dismissPreviewRestore) {
   public static CancellationCapture resolve(BiFunction<BindingContext, BindingCommands, BindingAction.Result> dispatch,
      CancellationKeyContext facts) {
      var commands = new BindingCommands() {
         private CancellationCommand chosen;
         public boolean cancellation(CancellationCommand command) { chosen = command; return true; }
         public void replayPress(long time) { throw new IllegalStateException("Cancellation capture cannot replay pointer input"); }
      };
      var result = dispatch.apply(facts.freeze("cancel_capture"), commands);
      boolean dismiss = commands.chosen == CancellationCommand.REQUEST_CANCEL;
      return new CancellationCapture(result, commands.chosen, dismiss && facts.operationRestore(), dismiss && facts.previewRestore());
   }

   public Optional<CancellationIntent> intent() {
      return !result.handled() || command == null || command == CancellationCommand.CLEAR_ENTRY ? Optional.empty()
         : Optional.of(new CancellationIntent(command, dismissOperationRestore, dismissPreviewRestore));
   }

   public boolean capturesVanillaDrop(boolean matchesDropKey) {
      return result.consumesVanilla() && matchesDropKey;
   }
}
