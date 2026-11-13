package io.github.fastformer.core.lifecycle;

/** Resources owned by one scope. Durable work must be transferred before that scope is discarded. */
public interface Scoped {
   default void onDetach() {}
   default void onDimensionChanged() {}
   void onDiscard();
}
