package io.github.fastformer.fastplace;

public enum GeometryMode implements TranslatableText {
   WALL("fastformer.geometry.mode.wall"),
   POLYHEDRON("fastformer.geometry.mode.polyhedron"),
   CONE_PRISM("fastformer.geometry.mode.cone_prism"),
   COMPOUND("fastformer.geometry.mode.compound"),
   CONVEX_POLYHEDRON("fastformer.geometry.mode.convex_polyhedron");

   private final String translationKey;

   GeometryMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
