package io.github.fastformer.client.interaction.binding.action;

import net.minecraft.resources.ResourceLocation;

/** The input owner supplies commands; binding actions never look up global input state. */
public interface BindingCommands {
   boolean start(ResourceLocation profile);
   boolean point();
   boolean middleConfirm();
   boolean undoPoint();
   boolean cancel();
   boolean cycleMode();
   default boolean tool(ToolCommand command) { return false; }
   void replayPress(long occurredAtNanos);
}
