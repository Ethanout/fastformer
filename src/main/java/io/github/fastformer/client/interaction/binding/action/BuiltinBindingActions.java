package io.github.fastformer.client.interaction.binding.action;

import com.mojang.serialization.Codec;
import io.github.fastformer.common.interaction.binding.SessionProfiles;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** Adapts data actions to the existing input owner's atomic operations. */
public final class BuiltinBindingActions {
   private BuiltinBindingActions() {}

   public static BindingActions create() {
      BindingActions actions = new BindingActions();
      actions.register(id("quick_shape/start"), ResourceLocation.CODEC.optionalFieldOf("profile", SessionProfiles.NONE).codec(),
         profile -> (context, commands) -> result(commands.start(profile)));
      register(actions, "quick_shape/point", BindingCommands::point);
      register(actions, "quick_shape/middle_confirm", BindingCommands::middleConfirm);
      register(actions, "quick_shape/undo_point", BindingCommands::undoPoint);
      register(actions, "session/cancel", BindingCommands::cancel);
      register(actions, "mode/cycle", BindingCommands::cycleMode);
      register(actions, "quick_replace/toggle", commands -> commands.tool(ToolCommand.TOGGLE_REPLACEMENT));
      registerPassVanilla(actions, "quick_replace/begin_stroke", commands -> commands.tool(ToolCommand.BEGIN_REPLACEMENT));
      register(actions, "quick_replace/apply", commands -> commands.tool(ToolCommand.APPLY_REPLACEMENT));
      registerPassVanilla(actions, "quick_replace/end_stroke", commands -> commands.tool(ToolCommand.END_REPLACEMENT));
      register(actions, "history/undo", commands -> commands.tool(ToolCommand.HISTORY_UNDO));
      register(actions, "history/redo", commands -> commands.tool(ToolCommand.HISTORY_REDO));
      register(actions, "smart_fill/start", commands -> commands.tool(ToolCommand.START_FILL));
      register(actions, "smart_fill/confirm", commands -> commands.tool(ToolCommand.CONFIRM_FILL));
      register(actions, "input/capture_swap_key", commands -> commands.tool(ToolCommand.CAPTURE_SWAP_KEY));
      register(actions, "workspace/copy", commands -> commands.workspace(WorkspaceCommand.COPY));
      register(actions, "workspace/paste", commands -> commands.workspace(WorkspaceCommand.PASTE));
      register(actions, "workspace/toggle_select_all", commands -> commands.workspace(WorkspaceCommand.TOGGLE_SELECT_ALL));
      register(actions, "workspace/duplicate", commands -> commands.workspace(WorkspaceCommand.DUPLICATE));
      register(actions, "workspace/rotate", commands -> commands.workspace(WorkspaceCommand.ROTATE_SELECTION));
      register(actions, "workspace/flip", commands -> commands.workspace(WorkspaceCommand.FLIP_SELECTION));
      register(actions, "workspace/submission_pending", commands -> commands.workspace(WorkspaceCommand.REPORT_SUBMISSION_PENDING));
      register(actions, "quick_shape/line/point", commands -> commands.quickShapeLine(QuickShapeLineCommand.POINT));
      register(actions, "quick_shape/line/middle", commands -> commands.quickShapeLine(QuickShapeLineCommand.MIDDLE));
      register(actions, "quick_shape/line/begin_undo", commands -> commands.quickShapeLine(QuickShapeLineCommand.BEGIN_UNDO));
      register(actions, "quick_shape/line/end_undo", commands -> commands.quickShapeLine(QuickShapeLineCommand.END_UNDO));
      register(actions, "quick_shape/line/release", commands -> commands.quickShapeLine(QuickShapeLineCommand.RELEASE_POINTER));
      register(actions, "quick_shape/line/scroll_up", commands -> commands.quickShapeLine(QuickShapeLineCommand.SCROLL_UP));
      register(actions, "quick_shape/line/scroll_down", commands -> commands.quickShapeLine(QuickShapeLineCommand.SCROLL_DOWN));
      registerPassVanilla(actions, "quick_shape/line/yield_scroll", commands -> commands.quickShapeLine(QuickShapeLineCommand.YIELD_SCROLL));
      actions.register(id("pass"), Codec.unit(true), ignored -> (context, commands) -> BindingAction.Result.PASS);
      actions.register(id("vanilla"), Codec.unit(true), ignored -> (context, commands) -> BindingAction.Result.HANDLED_PASS_VANILLA);
      actions.register(id("block"), Codec.unit(true), ignored -> (context, commands) -> BindingAction.Result.HANDLED);
      return actions.freeze();
   }

   private static void register(BindingActions actions, String path, Predicate<BindingCommands> operation) {
      actions.register(id(path), Codec.unit(true), ignored -> (context, commands) -> result(operation.test(commands)));
   }

   private static void registerPassVanilla(BindingActions actions, String path, Predicate<BindingCommands> operation) {
      actions.register(id(path), Codec.unit(true), ignored -> (context, commands) ->
         operation.test(commands) ? BindingAction.Result.HANDLED_PASS_VANILLA : BindingAction.Result.PASS);
   }

   private static BindingAction.Result result(boolean handled) { return handled ? BindingAction.Result.HANDLED : BindingAction.Result.PASS; }
   private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("fastformer", path); }
}
