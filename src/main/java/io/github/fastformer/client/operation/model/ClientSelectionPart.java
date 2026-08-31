package io.github.fastformer.client.operation.model;

import io.github.fastformer.client.operation.selection.SelectionBaseline;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** One independently addressable workspace component. Selection state is client-owned. */
public record ClientSelectionPart(
   int id, Source source, OperationSelectionVolume selection,
   Map<BlockPos, ClientBlockSnapshot> blocks, WorkspaceTransform transform,
   boolean pendingDelete, Map<BlockPos, ClientBlockSnapshot> sourceSnapshot,
   SelectionBaseline baseline, Editability editability
) {
   private static final double ORTHOGONAL_EPSILON = 1.0E-5;
   public enum Editability { FREE, LOCKED }

   public ClientSelectionPart(int id, Source source, OperationSelectionVolume selection,
      Map<BlockPos, ClientBlockSnapshot> blocks, WorkspaceTransform transform, boolean pendingDelete) {
      this(id, source, selection, blocks, transform, pendingDelete, blocks, null, Editability.FREE);
   }

   public ClientSelectionPart {
      if (id < 0 || id > ClientOperationWorkspace.MAX_PARTS) throw new IllegalArgumentException("Part id is outside the workspace slot range");
      if (source == null || transform == null) throw new IllegalArgumentException("Part source and transform are required");
      blocks = immutable(blocks);
      sourceSnapshot = immutable(sourceSnapshot);
      if (editability == null) editability = Editability.FREE;
      if (baseline != null && editability != Editability.LOCKED) throw new IllegalArgumentException("A baseline requires a locked part");
   }

   public static ClientSelectionPart empty(Source source) {
      return new ClientSelectionPart(0, source, null, Map.of(), WorkspaceTransform.IDENTITY, false);
   }

   public ClientSelectionPart withId(int value) { return copy(value, this.source, this.selection, this.blocks, this.transform, this.pendingDelete, this.sourceSnapshot, this.baseline, this.editability); }

   public ClientSelectionPart withTransform(WorkspaceTransform value) {
      if (value == null) value = WorkspaceTransform.IDENTITY;
      boolean changed = !value.equals(this.transform);
      SelectionBaseline nextBaseline = this.baseline;
      Editability nextEditability = this.editability;
      if (changed && nextEditability == Editability.FREE && value.hasEffect()) {
         nextBaseline = new SelectionBaseline(this.selection, this.transform, this.sourceSnapshot);
         nextEditability = Editability.LOCKED;
      } else if (nextEditability == Editability.LOCKED && nextBaseline != null && nextBaseline.matches(this.selection, value, this.sourceSnapshot)) {
         nextBaseline = null;
         nextEditability = Editability.FREE;
      }
      return copy(this.id, this.source, this.selection, this.blocks, value, this.pendingDelete, this.sourceSnapshot, nextBaseline, nextEditability);
   }

   public ClientSelectionPart withTranslation(Vec3 value) { return this.withTransform(this.transform.withTranslation(value)); }
   public ClientSelectionPart withTranslation(BlockPos value) { return this.withTranslation(Vec3.atLowerCornerOf(value)); }
   public ClientSelectionPart withPendingDelete(boolean value) { return copy(this.id, this.source, this.selection, this.blocks, this.transform, value, this.sourceSnapshot, this.baseline, this.editability); }
   public ClientSelectionPart withSelection(OperationSelectionVolume value) {
      if (this.editability == Editability.LOCKED && !java.util.Objects.equals(this.selection, value)) return this;
      return copy(this.id, this.source, value, this.blocks, this.transform, this.pendingDelete, this.sourceSnapshot, this.baseline, this.editability);
   }

   public ClientSelectionPart withBlocks(Map<BlockPos, ClientBlockSnapshot> value) {
      Map<BlockPos, ClientBlockSnapshot> nextSource = this.baseline == null ? value : this.sourceSnapshot;
      return copy(this.id, this.source, this.selection, value, this.transform, this.pendingDelete, nextSource, this.baseline, this.editability);
   }

   public boolean canAdjustGeometry() { return this.editability == Editability.FREE && this.source == Source.WORLD && this.selection != null && this.axisAlignedCuboid() && !this.transform.hasEffect(); }
   public boolean transformed() { return this.transform.hasEffect(); }
   public boolean masksSourceBlocks() { return this.source == Source.WORLD && (this.transformed() || this.pendingDelete); }
   public boolean axisAlignedCuboid() { return this.selection != null && this.selection.mode() == io.github.fastformer.fastplace.OperationSelectionMode.CUBOID && orthogonal(this.transform.rotation().x) && orthogonal(this.transform.rotation().y) && orthogonal(this.transform.rotation().z); }
   public boolean orientedCuboid() { return this.selection != null && this.selection.mode() == io.github.fastformer.fastplace.OperationSelectionMode.CUBOID && !this.axisAlignedCuboid(); }

   private static boolean orthogonal(double radians) { double quarterTurns = radians / (Math.PI * 0.5); return Math.abs(quarterTurns - Math.rint(quarterTurns)) <= ORTHOGONAL_EPSILON; }
   private static ClientSelectionPart copy(int id, Source source, OperationSelectionVolume selection, Map<BlockPos, ClientBlockSnapshot> blocks, WorkspaceTransform transform, boolean pendingDelete, Map<BlockPos, ClientBlockSnapshot> sourceSnapshot, SelectionBaseline baseline, Editability editability) { return new ClientSelectionPart(id, source, selection, blocks, transform, pendingDelete, sourceSnapshot, baseline, editability); }
   private static Map<BlockPos, ClientBlockSnapshot> immutable(Map<BlockPos, ClientBlockSnapshot> values) { LinkedHashMap<BlockPos, ClientBlockSnapshot> copy = new LinkedHashMap<>(); if (values != null) values.forEach((pos, snapshot) -> copy.put(pos.immutable(), snapshot)); return Map.copyOf(copy); }
   public enum Source { WORLD, CLIPBOARD }
}
