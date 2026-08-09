package io.github.fastformer.fastplace;

public enum ConePlaneMode implements TranslatableText {
   RADIUS("fastformer.geometry.cone_plane.radius"),
   DIAMETER("fastformer.geometry.cone_plane.diameter"),
   THREE_POINT("fastformer.geometry.cone_plane.three_point");

   private final String translationKey;

   ConePlaneMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }

   public int facePointCount() {
      return this == THREE_POINT ? 3 : 2;
   }

   public int requiredPoints() {
      return this.facePointCount() + 1;
   }

   public ConePrismStage stageFor(int pointCount) {
      if (pointCount < this.facePointCount()) {
         return ConePrismStage.FACE;
      }
      return pointCount < this.requiredPoints() ? ConePrismStage.BODY : ConePrismStage.ADJUST;
   }

   public ConePlaneMode next() {
      ConePlaneMode[] modes = values();
      return modes[Math.floorMod(this.ordinal() + 1, modes.length)];
   }
}
