package io.github.fastformer.fastplace.geometry;

public enum ControlPointState {
   CONFIRMED,
   PENDING,
   DERIVED;

   public boolean confirmed() {
      return this != PENDING;
   }

   public boolean participatesInBuild() {
      return this != DERIVED;
   }

   public boolean undoable() {
      return this == CONFIRMED;
   }
}
