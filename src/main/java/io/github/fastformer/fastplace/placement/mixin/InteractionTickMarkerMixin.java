package io.github.fastformer.fastplace.placement.mixin;

import io.github.fastformer.fastplace.interaction.ProtectedInteractionTick;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ScheduledTick.class)
public abstract class InteractionTickMarkerMixin implements ProtectedInteractionTick {
   @Unique private boolean fastformer$suppressNeighbors;
   @Override public boolean fastformer$suppressesNeighbors() { return fastformer$suppressNeighbors; }
   @Override public void fastformer$suppressNeighbors(boolean suppress) { fastformer$suppressNeighbors = suppress; }
}
