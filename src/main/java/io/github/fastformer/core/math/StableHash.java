package io.github.fastformer.core.math;

/** Stable 64-bit finalizer for deterministic visual seeds. */
public final class StableHash {
   private StableHash() {}

   public static long mix(long value) {
      value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
      value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
      return value ^ (value >>> 31);
   }
}
