package io.github.fastformer.common.geometry.generation.volume;

import io.github.fastformer.common.geometry.generation.face.*;
import io.github.fastformer.common.geometry.generation.face.entry.*;
import io.github.fastformer.common.geometry.generation.source.*;
import io.github.fastformer.core.math.SaturatedArithmetic;

record BlockColumnSpan(int min, int max) {
   static final BlockColumnSpan EMPTY = new BlockColumnSpan(Integer.MAX_VALUE, Integer.MIN_VALUE);

   boolean empty() {
      return this.min > this.max;
   }

   long length() {
      return empty() ? 0L : (long)this.max - this.min + 1L;
   }

   boolean contains(int value) {
      return !empty() && value >= this.min && value <= this.max;
   }
}
