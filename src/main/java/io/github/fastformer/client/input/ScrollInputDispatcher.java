package io.github.fastformer.client.input;

import io.github.fastformer.client.interaction.binding.tool.QuickReplaceBindingInput;
import io.github.fastformer.client.interaction.binding.tool.SmartFillBindingInput;
import io.github.fastformer.client.mode.ClientModes;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.operation.input.SelectionScrollAction;
import io.github.fastformer.common.selection.geometry.OperationGeometry;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent;
import io.github.fastformer.client.network.outbound.ServerGateway;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.protocol.c2s.geometry.ScrollCandidatePayload;
import net.minecraft.client.Minecraft;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.ScrollCommand;
import io.github.fastformer.client.interaction.binding.context.ScrollBindingContext;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import java.util.Comparator;

final class ScrollInputDispatcher {
   private ScrollInputDispatcher() { }

   static void dispatch(Minecraft minecraft, ClientInputSession session, ScrollInputSnapshot event) {
      var route = session.routing.dispatch(ClientInputStateMachine.InputKind.SCROLL);
      if (event.selectionAction() != null) {
         var action = event.selectionAction();
         if (route == ClientInputStateMachine.Dispatch.OPERATION
            && !ClientSessionSystem.instance().operations().workspaceSubmissionPending()
            && action.matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().workspace())) {
            switch (action.gesture()) {
               case SelectionScrollAction.Move move ->
                  ClientSessionSystem.instance().operations().transforms().scrollSelected(move.offset(), action.timestampNanos());
               case SelectionScrollAction.Stack stack ->
                  ClientSessionSystem.instance().operations().transforms().scrollStackSelected(stack, action.timestampNanos());
            }
         }
         return;
      }
      if (route != ClientInputStateMachine.Dispatch.BLOCKED
         && ServerGateway.supports(minecraft, ScrollCandidatePayload.TYPE)
         && ClientSessionSystem.instance().usesScrollContext()) {
         ServerGateway.send(new ScrollCandidatePayload(event.direction()));
         session.feedback().noteScroll(ClientSessionSystem.instance().previews().building(), System.nanoTime());
      }
   }

   static void onMouseScroll(MouseScrollingEvent event) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
       var replacement = QuickReplaceBindingInput.scroll(
          ClientModeKeyInput.replacementContext(Minecraft.getInstance(), inputOwner), event.getScrollDeltaY(),
          System.nanoTime(), ClientModeKeyInput.toolDispatcher(Minecraft.getInstance(), inputOwner));
      if (replacement.consumesVanilla()) event.setCanceled(true);
      if (replacement.owns()) return;
      InputWorldCleanup.synchronizeInputState();
      Minecraft minecraft = Minecraft.getInstance();
       var fill = SmartFillBindingInput.scroll(
          ClientModeKeyInput.ownsFillInput(inputOwner, ClientInputStateMachine.InputKind.SCROLL),
          ClientModeKeyInput.fillContext(minecraft, inputOwner, false), event.getScrollDeltaY(), System.nanoTime(),
          ClientModeKeyInput.toolDispatcher(minecraft, inputOwner));
       if (fill.owns()) {
          if (fill.consumesVanilla()) event.setCanceled(true);
          return;
       }
      var line = QuickShapeMouseInputController.scrollBindings(minecraft, inputOwner, event.getScrollDeltaY(), System.nanoTime());
      if (line.owns()) {
         if (line.consumesVanilla()) event.setCanceled(true);
         return;
      }
      boolean sessionReady = minecraft.player != null && minecraft.screen == null;
      boolean candidateChannelAvailable = minecraft.getConnection() != null
         && ServerGateway.supports(minecraft, ScrollCandidatePayload.TYPE);
      var context = new ScrollBindingContext(
         ClientModes.registry().id(inputOwner.routing.state()).value().toString(),
         inputOwner.routing.dispatch(ClientInputStateMachine.InputKind.SCROLL).name().toLowerCase(java.util.Locale.ROOT),
         sessionReady && ClientSessionSystem.instance().operations().active(),
         sessionReady && candidateChannelAvailable && ClientSessionSystem.instance().usesScrollContext(),
         ModifierCommandDispatcher.modifierInputRoute(ModifierCommandDispatcher.ModifierInput.SCROLL) == ModifierCommandDispatcher.ModifierInputRoute.VANILLA,
         sessionReady && InteractionContext.vanillaOwnsPointer(minecraft), event.getScrollDeltaY() != 0).freeze();
      var result = inputOwner.bindings.onShapeSignal(minecraft, inputOwner, "scroll",
         event.getScrollDeltaY() < 0 ? "scroll_down" : "scroll_up", context, new BindingCommands() {
            public boolean scroll(ScrollCommand command) { return execute(minecraft, inputOwner, command); }
            public void replayPress(long time) { throw new IllegalStateException("Wheel input cannot replay a pointer press"); }
         });
      if (result.consumesVanilla()) event.setCanceled(true);
   }

   private static boolean execute(Minecraft minecraft, ClientInputSession inputOwner, ScrollCommand command) {
      switch (command) {
         case WORKSPACE_UP, WORKSPACE_DOWN -> {
            int direction = command == ScrollCommand.WORKSPACE_UP ? 1 : -1;
            BlockPos offset = OperationGeometry.viewAxisStep(minecraft.player.getViewVector(1.0F), direction);
            var workspace = ClientSessionSystem.instance().operations().workspace();
            var reference = workspace.selectedParts().stream()
               .min(Comparator.comparingInt(ClientSelectionPart::id)).orElse(null);
            SelectionScrollAction.Gesture gesture = ModifierCommandDispatcher.physicalCtrlDown(minecraft, false) && reference != null
               ? SelectionScrollAction.stackAlongView(reference.transform(), minecraft.player.getViewVector(1.0F), direction)
               : new SelectionScrollAction.Move(offset);
            var action = SelectionScrollAction.capture(ClientSessionSystem.instance().operations().interactionScene().owner(),
               ClientSessionSystem.instance().operations().workspace(), gesture);
            if (action.isPresent() && !ClientSessionSystem.instance().operations().workspaceSubmissionPending()) {
               inputOwner.postScroll(new ScrollInputSnapshot(direction, action.orElseThrow()));
               return true;
            }
            return false;
         }
         case CANDIDATE_UP, CANDIDATE_DOWN -> {
            inputOwner.postScroll(new ScrollInputSnapshot(command == ScrollCommand.CANDIDATE_UP ? 1 : -1));
            if (inputOwner.modifier.held()) {
               inputOwner.modifier.consume();
            }
         }
         case YIELD_TO_VANILLA -> {
            inputOwner.modifier.consume();
         }
      }
      return true;
   }
}
