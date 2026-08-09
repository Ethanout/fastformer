package io.github.fastformer.fastplace;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public interface TranslatableText {
   String translationKey();

   default MutableComponent text() {
      return Component.translatable(this.translationKey());
   }
}

