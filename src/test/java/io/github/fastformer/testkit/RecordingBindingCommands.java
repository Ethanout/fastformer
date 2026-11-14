package io.github.fastformer.testkit;

import static org.junit.jupiter.api.Assertions.fail;

import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.ConfirmationCommand;
import io.github.fastformer.client.interaction.binding.action.WorkspaceCommand;
import io.github.fastformer.client.interaction.binding.action.HistoryConflictCommand;
import io.github.fastformer.client.interaction.binding.action.CancellationCommand;
import io.github.fastformer.client.interaction.binding.action.ScrollCommand;
import net.minecraft.resources.ResourceLocation;

/** Records key actions without touching Minecraft state or replaying pointer input. */
public final class RecordingBindingCommands implements BindingCommands {
   private WorkspaceCommand workspace;
   private ConfirmationCommand confirmation;
   private HistoryConflictCommand historyConflict;
   private CancellationCommand cancellation;
   private ScrollCommand scroll;

   public WorkspaceCommand workspace() { return workspace; }
   public ConfirmationCommand confirmation() { return confirmation; }
   public HistoryConflictCommand historyConflict() { return historyConflict; }
   public CancellationCommand cancellation() { return cancellation; }
   public ScrollCommand scroll() { return scroll; }
   public void clear() { workspace = null; confirmation = null; historyConflict = null; cancellation = null; scroll = null; }

   public boolean start(ResourceLocation profile) { return false; }
   public boolean point() { return false; }
   public boolean middleConfirm() { return false; }
   public boolean undoPoint() { return false; }
   public boolean cancel() { return false; }
   public boolean cycleMode() { return false; }
   public boolean workspace(WorkspaceCommand command) { workspace = command; return true; }
   public boolean confirmation(ConfirmationCommand command) { confirmation = command; return true; }
   public boolean historyConflict(HistoryConflictCommand command) { historyConflict = command; return true; }
   public boolean cancellation(CancellationCommand command) { cancellation = command; return true; }
   public boolean scroll(ScrollCommand command) { scroll = command; return true; }
   public void replayPress(long time) { fail("Key bindings must not replay pointer input"); }
}
