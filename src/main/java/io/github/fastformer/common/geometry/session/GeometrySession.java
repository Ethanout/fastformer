package io.github.fastformer.common.geometry.session;

import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.ControlPointRole;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Owns one geometry draft and its current typed shape. */
public final class GeometrySession {
   private GeometryShapeState state = GeometryShapeState.create(GeometryMode.WALL);
   private UUID draftId = UUID.randomUUID();

   public UUID draftId() { return draftId; }
   public GeometryMode mode() { return state.mode(); }
   public GeometryShapeState shape() { return state; }
   public List<BlockPos> points() { return state.blockPoints(); }
   public List<Vec3> pointLocations() { return state.pointLocations(); }
   public List<ControlPointRole> pointRoles() { return state.pointRoles(); }
   public boolean closed() { return state.closed(); }

   public WallGeometryState wall() { return require(WallGeometryState.class); }
   public ConePrismGeometryState cone() { return require(ConePrismGeometryState.class); }
   public PolyhedronGeometryState polyhedron() { return require(PolyhedronGeometryState.class); }
   public CompoundGeometryState compound() { return require(CompoundGeometryState.class); }
   public ConvexPolyhedronGeometryState convex() { return require(ConvexPolyhedronGeometryState.class); }

   private <S extends GeometryShapeState> S require(Class<S> type) {
      if (!type.isInstance(state)) throw new IllegalStateException(
         "Geometry mode " + mode() + " does not own " + type.getSimpleName());
      return type.cast(state);
   }

   public void setMode(GeometryMode mode) {
      state = GeometryShapeState.create(mode == null ? GeometryMode.WALL : mode);
      draftId = UUID.randomUUID();
   }

   public void addOrClose(BlockPos point) {
      state.beforePointChange();
      BlockPos value = point.immutable();
      if (state.points().size() >= 3 && state.points().getFirst().block().equals(value)) close();
      else addPoint(value);
   }

   public void addPoint(BlockPos point) { addPoint(Vec3.atCenterOf(point)); }
   public void addPoint(Vec3 point) { state.beforePointChange(); state.addPoint(point); }
   public void addPoint(Vec3 point, ControlPointRole role) { state.beforePointChange(); state.addPoint(point, role); }

   public boolean close() {
      if (state.closed()) return false;
      state.setClosed(true);
      state.beforePointChange();
      return true;
   }

   public boolean removeOrUndo(BlockPos point) { return removeOrUndo(point, true); }
   public boolean undoStep() { return removeOrUndo(null, false); }
   public boolean canUndoStep() { return true; }

   private boolean removeOrUndo(BlockPos point, boolean useHitPoint) {
      if (!state.removeOrUndo(point, useHitPoint)) return false;
      state.beforePointChange();
      return true;
   }
}
