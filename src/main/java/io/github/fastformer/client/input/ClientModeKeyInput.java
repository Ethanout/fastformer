package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;
import io.github.fastformer.client.input.policy.CancelInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.placement.ClientForcedPlacement;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.protocol.c2s.placement.QuitFastPlacePayload;
import net.neoforged.neoforge.client.event.InputEvent.Key;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.context.ConfirmationKeyContext;
import io.github.fastformer.client.interaction.binding.context.ReplacementContext;
import io.github.fastformer.client.interaction.binding.context.ToolKeyContext;
import io.github.fastformer.client.interaction.binding.context.WorkspaceKeyContext;
import io.github.fastformer.client.interaction.binding.tool.ToolBindingCommand;
import io.github.fastformer.client.mode.ClientModes;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.session.ClientSessionSystem;
import java.util.HashSet;
import java.util.Map;
import net.minecraft.client.Minecraft;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.KeyMapping;

/** Freezes input-owner facts and delegates tool decisions to resource bindings. */
final class ClientModeKeyInput {
   private ClientModeKeyInput() {}

   /** The preview query treats repeats as presses only to release a captured movement mapping. */
   static boolean workspaceOwnsKey(Minecraft minecraft, ClientInputSession owner, KeyboardInputSnapshot input) {
      var restore = confirmation(minecraft, owner, input, "restore", true);
      if (restore.handled()) return restore.consumesVanilla();
      var confirm = confirmation(minecraft, owner, input, "confirmation", true);
      return confirm.handled() ? confirm.consumesVanilla() : workspace(minecraft, owner, input, true);
   }

   private static BindingAction.Result confirmation(Minecraft minecraft, ClientInputSession owner, KeyboardInputSnapshot input,
      String phase, boolean probe) {
      return owner.bindings.onConfirmationKey(minecraft, owner,
         Set.of(InputConstants.getKey(input.key(), input.scanCode()).getName()), phase, () -> {
            var operations = ClientSessionSystem.instance().operations();
            var keyRoute = owner.routing.dispatch(InputKind.KEY);
            boolean submissionAllowed = owner.routing.dispatch(InputKind.SUBMIT) != Dispatch.BLOCKED;
            boolean canConfirm = phase.equals("confirmation") && submissionAllowed && minecraft.player != null
               && minecraft.screen == null && minecraft.getConnection() != null && ClientPlacementRouter.canConfirm(minecraft);
            return new ConfirmationKeyContext(ClientModes.registry().id(owner.routing.state()).value().toString(),
               keyRoute.name().toLowerCase(Locale.ROOT), submissionAllowed, minecraft.screen == null, minecraft.player != null,
               minecraft.getConnection() != null, operations.reconnectRestorePending()
                  || ClientSessionSystem.instance().previews().reconnectRestorePending(),
               canConfirm, canConfirm && InteractionContext.vanillaOwnsPointer(minecraft),
               probe && input.action() == 2 ? 1 : input.action(), input.controlDown(), input.altDown(), input.shiftDown()).freeze(phase);
         }, input, probe);
   }

   static boolean handleWorkspace(Minecraft minecraft, ClientInputSession owner, KeyboardInputSnapshot input) {
      return workspace(minecraft, owner, input, false);
   }

   private static boolean workspace(Minecraft minecraft, ClientInputSession owner, KeyboardInputSnapshot input, boolean probe) {
      var operations = ClientSessionSystem.instance().operations();
      var route = owner.routing.dispatch(InputKind.KEY);
      var context = new WorkspaceKeyContext(ClientModes.registry().id(owner.routing.state()).value().toString(),
         route.name().toLowerCase(Locale.ROOT), owner.routing.dispatch(InputKind.PASTE_WORKSPACE) != Dispatch.BLOCKED,
         operations.active() || operations.selectionEdits().selectionDraftActive() || operations.selectionEdits().remoteSelectionPointing(),
         operations.workspaceEditInProgress(), operations.workspaceSubmissionPending(), minecraft.screen == null, minecraft.player != null,
         probe && input.action() == 2 ? 1 : input.action(), input.controlDown(), input.altDown(), input.shiftDown(),
         operations.workspaceUndoDepth()).freeze();
      return owner.bindings.onWorkspaceKey(minecraft, owner,
         Set.of(InputConstants.getKey(input.key(), input.scanCode()).getName()), context, input.occurredAtNanos(), probe);
   }

   static ReplacementContext replacementContext(Minecraft minecraft, ClientInputSession owner) {
      return new ReplacementContext(
         QuickReplaceMode.active(), QuickReplaceMode.canReplace(minecraft), minecraft.player != null && minecraft.player.isCreative(),
         ClientSessionSystem.instance().previews().building().enabled(),
         minecraft.screen == null, minecraft.isWindowActive(), owner.routing.routesToVanilla(InputKind.INTERACTION),
         minecraft.options.keyUse.isDown());
   }

   static boolean ownsFillInput(ClientInputSession owner, InputKind kind) {
      return owner.smartFill().active() && ClientModes.registry().policy(owner.routing.state()).dataDrivenInputs().contains(kind);
   }

   static BindingContext fillContext(Minecraft minecraft, ClientInputSession owner, boolean standardMouseButton) {
      return new BindingContext(Map.of(
         "session", new JsonPrimitive("fastformer:smart_fill"),
         "fill_ready", new JsonPrimitive(owner.smartFill().ready()),
         "screen", new JsonPrimitive(minecraft.screen != null),
         "player_present", new JsonPrimitive(minecraft.player != null),
         "standard_mouse_button", new JsonPrimitive(standardMouseButton)));
   }

   static ToolBindingCommand toolDispatcher(Minecraft minecraft, ClientInputSession owner) {
      return (name, gesture, context, time) -> owner.dispatchToolBinding(minecraft, name, gesture, context, time);
   }

   /** Captures only data rules that explicitly run before vanilla key clicks are processed. */
   static boolean capture(Minecraft minecraft, ClientInputSession session, KeyboardInputSnapshot input) {
      return dispatch(minecraft, session, input, "capture");
   }

   static boolean handle(Minecraft minecraft, ClientInputSession session, KeyboardInputSnapshot input) {
      return dispatch(minecraft, session, input, "dispatch");
   }

   private static boolean dispatch(Minecraft minecraft, ClientInputSession session, KeyboardInputSnapshot input, String phase) {
      boolean replacing = QuickReplaceMode.active();
      var fill = session.smartFill();
      // Existing modal tools own their dedicated keys even while a task snapshot arrives.
      State mode = replacing ? State.QUICK_REPLACE : fill.active() ? State.FILL_PREVIEW : session.routing.state();
      boolean idle = !replacing && !fill.active() && session.routing.dispatch(InputKind.KEY) == Dispatch.VANILLA;
      var context = new ToolKeyContext(ClientModes.registry().id(mode).value().toString(), minecraft.screen == null,
         minecraft.player != null, idle, fill.ready(), idle && fill.canStart(minecraft), input.action(),
         input.controlDown(), input.altDown(), input.shiftDown()).freeze(phase);
      var names = new HashSet<String>();
      names.add(InputConstants.getKey(input.key(), input.scanCode()).getName());
      if (minecraft.options.keySwapOffhand.matches(input.key(), input.scanCode())) names.add("@swap_offhand");
      return session.bindings.onToolKey(minecraft, session, names, context, input.occurredAtNanos());
   }

   static void onKey(Key event) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.options.keyUse.matches(event.getKey(), event.getScanCode())) {
         ClientForcedPlacement.input(minecraft.player, event.getAction());
      }
      // Escape is captured before the pause screen opens.
      if (event.getKey() == CancelInputSemantics.ESCAPE_KEY) return;
      long window = minecraft.getWindow().getWindow();
      KeyboardInputSnapshot input = KeyboardInputSnapshot.capture(
         event.getKey(), event.getScanCode(), event.getAction(), event.getModifiers(), System.nanoTime(),
         InputConstants.isKeyDown(window, 342), InputConstants.isKeyDown(window, 346),
         InputConstants.isKeyDown(window, 341), InputConstants.isKeyDown(window, 345)
      );
      boolean shortcutOwnsMovementKey = false;
      if (minecraft.player != null && minecraft.screen == null) {
         InputWorldCleanup.synchronizeInputState();
         shortcutOwnsMovementKey = ClientModeKeyInput.workspaceOwnsKey(minecraft, inputOwner, input);
      }
      MovementKeyInput.reconcile(new KeyMapping[]{
         minecraft.options.keyUp, minecraft.options.keyDown, minecraft.options.keyLeft, minecraft.options.keyRight
      }, input, shortcutOwnsMovementKey);
      if (minecraft.player != null && minecraft.screen == null && input.action() == 1
          && shortcutOwnsMovementKey
         && minecraft.options.keySwapOffhand.matches(input.key(), input.scanCode())) {
         minecraft.options.keySwapOffhand.consumeClick();
      }
      if (ClientModeKeyInput.capture(minecraft, inputOwner, input)) {
         InputWorldCleanup.synchronizeInputState();
         return;
      }
      if (input.action() == 1) {
         input = input.withQuickShapeSubmission(ClientSessionSystem.instance().previews().buildingSubmission().orElse(null));
      }
      if (input.key() == CancelInputSemantics.CANCEL_KEY && input.action() == CancelInputSemantics.PRESS) {
         if (inputOwner.bindings.onKey(minecraft, inputOwner, input.key(), input.action(), System.nanoTime())) {
            if (cancelKeyCarriesVanillaDrop(minecraft, input.key())) minecraft.options.keyDrop.consumeClick();
            return;
         }
         inputOwner.idleEntry.clear();
         inputOwner.entryPressGuard.clear();
         inputOwner.cuboidPointPressGuard.clear();
         if (HistoryConflictConfirmation.pending() != null) {
            if (cancelKeyCarriesVanillaDrop(minecraft, input.key())) minecraft.options.keyDrop.consumeClick();
            inputOwner.postKeyboard(input);
            return;
         }
         var cancellation = captureCancel(minecraft, input);
         if (cancellation == null) return;
         input = input.withCancellation(cancellation);
         if (CancelInputSemantics.capturesVanillaDrop(cancellation,
            cancelKeyCarriesVanillaDrop(minecraft, input.key()))) minecraft.options.keyDrop.consumeClick();
      }
      inputOwner.postKeyboard(input);
   }

   static CancelInputSemantics.Decision captureCancel(Minecraft minecraft, KeyboardInputSnapshot input) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (minecraft == null || minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) return null;
      InputWorldCleanup.synchronizeInputState();
      ClientInputStateMachine.Dispatch route = inputOwner.routing.dispatch(ClientInputStateMachine.InputKind.CANCEL);
      boolean operationRestore = ClientSessionSystem.instance().operations().reconnectRestorePending();
      boolean previewRestore = ClientSessionSystem.instance().previews().reconnectRestorePending();
      boolean owned = route == ClientInputStateMachine.Dispatch.CANCEL || inputOwner.canCancelPendingSessionStart()
         || operationRestore || previewRestore;
      var decision = CancelInputSemantics.decide(input.action(), input.key(), true,
         ServerGateway.supports(minecraft, QuitFastPlacePayload.TYPE), owned,
         ClientSessionSystem.instance().operations().workspaceSubmissionPending(), operationRestore, previewRestore);
      return decision.command() == CancelInputSemantics.Command.IGNORE ? null : decision;
   }

   static void handleKey(Minecraft minecraft, KeyboardInputSnapshot event) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (inputOwner.bindings.onKey(minecraft, inputOwner, event.key(), event.action(), System.nanoTime())) {
         if (cancelKeyCarriesVanillaDrop(minecraft, event.key())) minecraft.options.keyDrop.consumeClick();
         return;
      }
      if (HistoryConflictConfirmation.handleKey(minecraft, event)) return;
      InputWorldCleanup.synchronizeInputState();
      if (ClientModeKeyInput.handle(minecraft, inputOwner, event)) {
         InputWorldCleanup.synchronizeInputState();
         return;
      }
      ClientInputStateMachine.Dispatch inputRoute = inputOwner.routing.dispatch(ClientInputStateMachine.InputKind.KEY);
      boolean operationSession = inputRoute == ClientInputStateMachine.Dispatch.OPERATION;
      boolean altKey = event.altKey();
      boolean ctrlKey = event.controlKey();
      boolean controlDown = event.controlDown();
      if (event.action() == 1 && event.altDown() && !altKey && !ctrlKey) {
         inputOwner.modifier.consume();
      }
      if (confirmation(minecraft, inputOwner, event, "restore", false).handled()) return;
      CancelInputSemantics.Decision cancel = event.cancellation();
      if (cancel != null && ClientSessionSystem.instance().operations().workspaceSubmissionPending()) {
         ClientInteractionFeedback.show(minecraft, "fastformer.message.operation_submit_pending");
         return;
      }
      if (cancel != null && operationSession && event.key() == 81) {
         InputWorldCleanup.cancelOperationGesture(minecraft);
         ClientSessionSystem.instance().operations().selectionEdits().cancelLastSelection();
         if (ServerGateway.supports(minecraft, QuitFastPlacePayload.TYPE)) {
            ServerGateway.send(QuitFastPlacePayload.INSTANCE);
         }
         return;
      }
      if (cancel != null) {
         cancelActiveSession(
            minecraft, cancel.dismissOperationRestore(), cancel.dismissPreviewRestore()
         );
         return;
      }
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         return;
      }
      if (minecraft.player != null && minecraft.screen == null && event.action() == 1) {
         if (confirmation(minecraft, inputOwner, event, "confirmation", false).handled()) return;
         if (ClientModeKeyInput.handleWorkspace(minecraft, inputOwner, event)) {
            return;
         }
      }
      if (altKey
         && (event.action() == 0 || event.action() == 1)
         && minecraft.player != null
         && minecraft.getConnection() != null) {
         ModifierCommandDispatcher.handleAltTransition(minecraft, event.altDown(), event.occurredAtNanos());
      }
      if ((altKey || ctrlKey)
         && (event.action() == 0 || event.action() == 1)
         && minecraft.player != null
         && minecraft.getConnection() != null) {
         ModifierCommandDispatcher.handleRadialKeyTransition(minecraft, controlDown && event.altDown());
      }
   }

   static boolean cancelActiveSession(
      Minecraft minecraft, boolean dismissOperationRestore, boolean dismissPreviewRestore
   ) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (inputOwner.smartFill().active()) {
         inputOwner.smartFill().cancel(minecraft);
         InputWorldCleanup.synchronizeInputState();
         return true;
      }
      boolean cancellingQuickShape = inputOwner.quickShapeSubmission.active()
         || inputOwner.ownsQuickShapeStart()
         || inputOwner.routing.ownsQuickShapeSubmission();
      boolean cancelled = inputOwner.cancel();
      if (dismissOperationRestore) ClientSessionSystem.instance().operations().dismissReconnectRestore();
      if (dismissPreviewRestore) ClientSessionSystem.instance().previews().dismissReconnectRestore();
      if (!cancelled) {
         return false;
      }
      InputWorldCleanup.cancelOperationGesture(minecraft);
      ModifierCommandDispatcher.resetModifierState(minecraft);
      if (!cancellingQuickShape) ClientSessionSystem.instance().operations().clearWorkspace();
      inputOwner.feedback().clear();
      if (ServerGateway.supports(minecraft, QuitFastPlacePayload.TYPE)) {
         ServerGateway.send(QuitFastPlacePayload.INSTANCE);
      }
      return true;
   }

   static boolean cancelKeyCarriesVanillaDrop(Minecraft minecraft, int eventKey) {
      if (minecraft.options.keyDrop.isUnbound()) {
         return false;
      }
      return minecraft.options.keyDrop.getKey()
         .equals(InputConstants.Type.KEYSYM.getOrCreate(eventKey));
   }

}
