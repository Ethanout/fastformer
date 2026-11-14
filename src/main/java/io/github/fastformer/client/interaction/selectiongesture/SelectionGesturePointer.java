package io.github.fastformer.client.interaction.selectiongesture;

/** Borrows the environment's existing pointer token rather than creating another lifetime. */
public interface SelectionGesturePointer {
   enum Kind { FACE, GIZMO }
   long begin(Kind kind);
   boolean owns(long token, Kind kind);
   void finish(long token);
   void consumeModifier();
}
