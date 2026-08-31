package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public interface TranslatableText {
   String translationKey();

   default MutableComponent text() {
      return Component.translatable(this.translationKey());
   }
}

