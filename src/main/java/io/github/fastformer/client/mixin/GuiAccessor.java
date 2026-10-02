package io.github.fastformer.client.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads vanilla text lifetimes so mod hints can share the same screen space. */
@Mixin(Gui.class)
public interface GuiAccessor {
   @Accessor("toolHighlightTimer") int fastformer$toolHighlightTimer();
   @Accessor("lastToolHighlight") ItemStack fastformer$lastToolHighlight();
   @Accessor("overlayMessageTime") int fastformer$overlayMessageTime();
}
