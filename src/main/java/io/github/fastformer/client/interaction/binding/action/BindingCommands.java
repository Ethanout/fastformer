package io.github.fastformer.client.interaction.binding.action;

import net.minecraft.resources.ResourceLocation;
import io.github.fastformer.client.interaction.binding.action.entry.IdleCommand;
import io.github.fastformer.client.interaction.binding.action.entry.IdleEntryParameters;

/** The input owner supplies commands; binding actions never look up global input state. */
public interface BindingCommands {
   /** Logical selection operations are independent of their physical mouse bindings. */
   enum SelectionBindingCommand {
      DRAFT_LEFT, DRAFT_RIGHT, DRAFT_MIDDLE, POINT_LEFT, POINT_RIGHT, POINT_MIDDLE,
      POINTER_SHRINK, POINTER_EXPAND, GIZMO, REMOTE_GIZMO, REMOTE_POINT_LEFT, REMOTE_POINT_RIGHT,
      REMOTE_FACE_SHRINK, REMOTE_FACE_EXPAND, REMOTE_SELECT, REMOTE_EDGE, REMOTE_NEXT, REMOTE_EXTRA,
      REMOTE_CLOSE, BEGIN_UNDO, END_UNDO, CAPTURE, RELEASE
   }
   default boolean start(ResourceLocation profile) { return false; }
   default boolean point() { return false; }
   default boolean middleConfirm() { return false; }
   default boolean undoPoint() { return false; }
   default boolean cancel() { return false; }
   default boolean cycleMode() { return false; }
   default boolean tool(ToolCommand command) { return false; }
   default boolean workspace(WorkspaceCommand command) { return false; }
   default boolean confirmation(ConfirmationCommand command) { return false; }
   default boolean historyConflict(HistoryConflictCommand command) { return false; }
   default boolean cancellation(CancellationCommand command) { return false; }
   default boolean quickShapeBinding(QuickShapeBindingCommand command) { return false; }
   default boolean scroll(ScrollCommand command) { return false; }
   default boolean geometry(GeometryBindingCommand command, int durationMillis) { return false; }
   default BindingAction.Result idle(IdleCommand command, IdleEntryParameters parameters) { return BindingAction.Result.PASS; }
   default boolean selection(SelectionBindingCommand command,
      int durationMillis) { return false; }
   void replayPress(long occurredAtNanos);
}
