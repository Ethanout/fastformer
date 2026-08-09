package io.github.fastformer.fastplace.geometry.generation;

final class GenerationMath {
   private GenerationMath() {
   }

   static long saturatedMultiply(long left, long right) {
      return left > 0L && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
   }

   static long saturatedAdd(long left, long right) {
      return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }
}
