package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.ConePlaneMode;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;

public record ConePrismParameters(
   List<Vec3> facePoints,
   Optional<Vec3> heightPoint,
   int shapeVariant,
   ConePlaneMode planeMode,
   double radius,
   double scaleX,
   double scaleZ,
   double topScaleOffset,
   Vec3 topOffset,
   double rotationRadians
) {
   public ConePrismParameters {
      facePoints = facePoints == null
         ? List.of()
         : facePoints.stream().map(GeometryNumbers::finiteOrZero).toList();
      heightPoint = heightPoint == null
         ? Optional.empty()
         : heightPoint.map(GeometryNumbers::finiteOrZero);
      planeMode = planeMode == null ? ConePlaneMode.RADIUS : planeMode;
      topOffset = GeometryNumbers.finiteOrZero(topOffset);
      rotationRadians = Double.isFinite(rotationRadians) ? Math.IEEEremainder(rotationRadians, Math.PI * 2.0) : 0.0;
   }

   public List<Vec3> points() {
      ArrayList<Vec3> result = new ArrayList<>(this.facePoints.size() + (this.heightPoint.isPresent() ? 1 : 0));
      result.addAll(this.facePoints);
      this.heightPoint.ifPresent(result::add);
      return List.copyOf(result);
   }
}
