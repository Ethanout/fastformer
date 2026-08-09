package io.github.fastformer.fastplace;

public enum PolyhedronSizeMode implements TranslatableText {
   RADIUS("fastformer.geometry.polyhedron_size.radius"),
   DIAMETER("fastformer.geometry.polyhedron_size.diameter");

   private final String translationKey;

   PolyhedronSizeMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }

   public PolyhedronSizeMode next() {
      PolyhedronSizeMode[] modes = values();
      return modes[Math.floorMod(this.ordinal() + 1, modes.length)];
   }
}
