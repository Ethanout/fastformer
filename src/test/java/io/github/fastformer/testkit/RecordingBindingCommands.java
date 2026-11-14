package io.github.fastformer.testkit;

import static org.junit.jupiter.api.Assertions.fail;

import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.ConfirmationCommand;
import io.github.fastformer.client.interaction.binding.action.WorkspaceCommand;
import net.minecraft.resources.ResourceLocation;

/** Records key actions without touching Minecraft state or replaying pointer input. */
public final class RecordingBindingCommands implements BindingCommands {
   private WorkspaceCommand workspace;
   private ConfirmationCommand confirmation;

   public WorkspaceCommand workspace() { return workspace; }
   public ConfirmationCommand confirmation() { return confirmation; }
   public void clear() { workspace = null; confirmation = null; }

   public boolean start(ResourceLocation profile) { return false; }
   public boolean point() { return false; }
   public boolean middleConfirm() { return false; }
   public boolean undoPoint() { return false; }
   public boolean cancel() { return false; }
   public boolean cycleMode() { return false; }
   public boolean workspace(WorkspaceCommand command) { workspace = command; return true; }
   public boolean confirmation(ConfirmationCommand command) { confirmation = command; return true; }
   public void replayPress(long time) { fail("Key bindings must not replay pointer input"); }
}
