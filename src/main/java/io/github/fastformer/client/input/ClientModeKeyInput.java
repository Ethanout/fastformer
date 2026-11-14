package io.github.fastformer.client.input;

import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
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

/** Freezes input-owner facts and delegates tool decisions to resource bindings. */
final class ClientModeKeyInput {
   private ClientModeKeyInput() {}

   /** The preview query treats repeats as presses only to release a captured movement mapping. */
   static boolean workspaceOwnsKey(Minecraft minecraft, ClientInputSession owner, KeyboardInputSnapshot input) {
      return workspace(minecraft, owner, input, true);
   }

   static boolean handleWorkspace(Minecraft minecraft, ClientInputSession owner, KeyboardInputSnapshot input) {
      return workspace(minecraft, owner, input, false);
   }

   private static boolean workspace(Minecraft minecraft, ClientInputSession owner, KeyboardInputSnapshot input, boolean probe) {
      var operations = ClientSessionSystem.instance().operations();
      var route = owner.routing.dispatch(InputKind.KEY);
      var context = new WorkspaceKeyContext(ClientModes.registry().id(owner.routing.state()),
         route.name().toLowerCase(java.util.Locale.ROOT), owner.routing.dispatch(InputKind.PASTE_WORKSPACE) != Dispatch.BLOCKED,
         operations.active() || operations.selectionEdits().selectionDraftActive() || operations.selectionEdits().remoteSelectionPointing(),
         operations.workspaceEditInProgress(), operations.workspaceSubmissionPending(), minecraft.screen == null, minecraft.player != null,
         probe && input.action() == 2 ? 1 : input.action(), input.controlDown(), input.altDown(), input.shiftDown()).freeze();
      return owner.bindings.onWorkspaceKey(minecraft, owner,
         java.util.Set.of(InputConstants.getKey(input.key(), input.scanCode()).getName()), context, input.occurredAtNanos(), probe);
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
      var context = new ToolKeyContext(ClientModes.registry().id(mode), minecraft.screen == null,
         minecraft.player != null, idle, fill.ready(), idle && fill.canStart(minecraft), input.action(),
         input.controlDown(), input.altDown(), input.shiftDown()).freeze(phase);
      var names = new HashSet<String>();
      names.add(InputConstants.getKey(input.key(), input.scanCode()).getName());
      if (minecraft.options.keySwapOffhand.matches(input.key(), input.scanCode())) names.add("@swap_offhand");
      return session.bindings.onToolKey(minecraft, session, names, context, input.occurredAtNanos());
   }
}
