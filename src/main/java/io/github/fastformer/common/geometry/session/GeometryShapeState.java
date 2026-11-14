package io.github.fastformer.common.geometry.session;

import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.GeometryPoint;
import io.github.fastformer.common.geometry.ControlPointRole;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public abstract sealed class GeometryShapeState permits WallGeometryState, PolyhedronGeometryState, ConePrismGeometryState,
   CompoundGeometryState, ConvexPolyhedronGeometryState {
   private final GeometryMode mode;
   private final ArrayList<GeometryPoint> points = new ArrayList<>();
   private boolean closed;

   GeometryShapeState(GeometryMode mode) {
      this.mode = mode;
   }

   static GeometryShapeState create(GeometryMode mode) {
      return switch (mode) {
         case WALL -> new WallGeometryState();
         case POLYHEDRON -> new PolyhedronGeometryState();
         case CONE_PRISM -> new ConePrismGeometryState();
         case COMPOUND -> new CompoundGeometryState();
         case CONVEX_POLYHEDRON -> new ConvexPolyhedronGeometryState();
      };
   }

   public final GeometryMode mode() {
      return this.mode;
   }

   final List<GeometryPoint> points() {
      return this.points;
   }

   public final List<BlockPos> blockPoints() {
      return this.points.stream().map(GeometryPoint::block).toList();
   }

   public final List<Vec3> pointLocations() {
      return this.points.stream().map(GeometryPoint::location).toList();
   }

   public final boolean closed() {
      return this.closed;
   }

   final void setClosed(boolean closed) {
      this.closed = closed;
   }

   final void clearPoints() {
      this.points.clear();
      this.closed = false;
   }

   public final void addOrClose(BlockPos point) {
      beforePointChange();
      BlockPos value = point.immutable();
      if (this.points.size() >= 3 && this.points.getFirst().block().equals(value)) close();
      else addPoint(value);
   }

   public final void addPoint(BlockPos point) {
      addPoint(Vec3.atCenterOf(point));
   }

   public final void addPoint(Vec3 point) {
      beforePointChange();
      GeometryPoint value = new GeometryPoint(point, this.nextRole());
      if (!this.closed && (this.points.isEmpty() || !this.points.getLast().location().equals(value.location()))) {
         this.points.add(value);
      }
   }

   public final void addPoint(Vec3 point, ControlPointRole role) {
      beforePointChange();
      GeometryPoint value = new GeometryPoint(point, role);
      if (!this.closed && (this.points.isEmpty() || !this.points.getLast().location().equals(value.location()))) {
         this.points.add(value);
      }
   }

   public final boolean close() {
      if (this.closed) return false;
      this.closed = true;
      beforePointChange();
      return true;
   }

   ControlPointRole nextRole() {
      return this.points.isEmpty() ? ControlPointRole.PRIMARY : ControlPointRole.SECONDARY;
   }

   boolean removeOrUndo(BlockPos point, boolean useHitPoint) {
      int index = -1;
      if (useHitPoint) {
         for (int i = 0; i < this.points.size(); i++) {
            if (this.points.get(i).block().equals(point)) {
               index = i;
               break;
            }
         }
      }
      if (index >= 0) this.points.remove(index);
      else if (!this.points.isEmpty()) this.points.removeLast();
      else return false;
      this.closed = false;
      afterPointRemoved();
      return true;
   }

   public final List<ControlPointRole> pointRoles() {
      return this.points.stream().map(GeometryPoint::role).toList();
   }

   void beforePointChange() { }
   void afterPointRemoved() { }
}
