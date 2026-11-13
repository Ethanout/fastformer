package io.github.fastformer.server.intent;

import io.github.fastformer.core.command.Intent;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.cone.ConePlaneMode;
import io.github.fastformer.fastplace.geometry.polyhedron.PolyhedronSizeMode;
import java.util.Objects;

/** Geometry edits shared by commands and other input sources. */
public final class GeometryIntents {
   private GeometryIntents() {}

   public record SelectMode(GeometryMode mode) implements Intent {
      public SelectMode { Objects.requireNonNull(mode, "mode"); }
   }

   public record ConePlane(ConePlaneMode mode) implements Intent {
      public ConePlane { Objects.requireNonNull(mode, "mode"); }
   }

   public record PolyhedronSize(PolyhedronSizeMode mode) implements Intent {
      public PolyhedronSize { Objects.requireNonNull(mode, "mode"); }
   }

   public record ConeEllipse(double x, double z) implements Intent {}
   public record Euler(double x, double y, double z) implements Intent {}
   public record Quaternion(double w, double x, double y, double z) implements Intent {}

   public record Matrix(double[] values) implements Intent {
      public Matrix { values = Objects.requireNonNull(values, "values").clone(); }
      @Override public double[] values() { return values.clone(); }
   }

   public record RotationStep(int axis, int steps, boolean fine) implements Intent {}
}
