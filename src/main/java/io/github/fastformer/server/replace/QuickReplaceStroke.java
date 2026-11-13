package io.github.fastformer.server.replace;
import io.github.fastformer.common.replace.QuickReplaceSweep;

import io.github.fastformer.server.history.memory.BlockEditHistoryGesture;
import net.minecraft.world.item.ItemStack;

/** Server-thread ownership of one physical press and its last admitted ray. */
final class QuickReplaceStroke {
   final long id;
   final BlockEditHistoryGesture history = new BlockEditHistoryGesture();
   QuickReplaceSweep.Sample previous;
   ItemStack item;
   long lastSampleTick = Long.MIN_VALUE;

   QuickReplaceStroke(long id) { this.id = id; }
}
