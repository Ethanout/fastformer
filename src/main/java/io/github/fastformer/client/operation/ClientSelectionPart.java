package io.github.fastformer.client.operation;

import java.util.LinkedHashMap;
import java.util.Map;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One independently addressable selection or pasted component. */
public record ClientSelectionPart(
   int id,
   Source source,
   OperationSelectionVolume selection,
   Map<BlockPos, ClientBlockSnapshot> blocks,
   WorkspaceTransform transform,
   boolean pendingDelete,
   AABB initialBounds,
   Map<BlockPos, ClientBlockSnapshot> initialBlocks,
   boolean transformBaselineFrozen
) {
   private static final double ORTHOGONAL_EPSILON = 1.0E-5;
   private static final double BOUNDS_EPSILON = 1.0E-7;

   public ClientSelectionPart(
      int id,
      Source source,
      OperationSelectionVolume selection,
      Map<BlockPos, ClientBlockSnapshot> blocks,
      WorkspaceTransform transform,
      boolean pendingDelete
   ) {
      this(id, source, selection, blocks, transform, pendingDelete, worldBounds(selection, transform), blocks, false);
   }

   public ClientSelectionPart {
      if (id < 0 || id > ClientOperationWorkspace.MAX_PARTS) {
         throw new IllegalArgumentException("Part id is outside the workspace slot range");
      }
      if (source == null || transform == null) {
         throw new IllegalArgumentException("Part source and transform are required");
      }
      LinkedHashMap<BlockPos, ClientBlockSnapshot> copy = new LinkedHashMap<>();
      if (blocks != null) {
         blocks.forEach((pos, snapshot) -> copy.put(pos.immutable(), snapshot));
      }
      blocks = Map.copyOf(copy);
      LinkedHashMap<BlockPos, ClientBlockSnapshot> initialCopy = new LinkedHashMap<>();
      if (initialBlocks != null) {
         initialBlocks.forEach((pos, snapshot) -> initialCopy.put(pos.immutable(), snapshot));
      }
      initialBlocks = Map.copyOf(initialCopy);
   }

   public static ClientSelectionPart empty(Source source) {
      return new ClientSelectionPart(0, source, null, Map.of(), WorkspaceTransform.IDENTITY, false, null, Map.of(), false);
   }

   public ClientSelectionPart withId(int value) {
      return copy(value, this.source, this.selection, this.blocks, this.transform, this.pendingDelete, this.initialBounds, this.initialBlocks, this.transformBaselineFrozen);
   }

   public ClientSelectionPart withTransform(WorkspaceTransform value) {
      boolean freeze = this.transformBaselineFrozen;
      AABB baseline = this.initialBounds;
      if (!freeze && value.hasEffect()) {
         baseline = worldBounds(this.selection, this.transform);
         freeze = true;
      }
      return copy(this.id, this.source, this.selection, this.blocks, value, this.pendingDelete, baseline, this.initialBlocks, freeze);
   }

   public ClientSelectionPart withTranslation(Vec3 value) {
      return this.withTransform(this.transform.withTranslation(value));
   }

   public ClientSelectionPart withTranslation(BlockPos value) {
      return this.withTranslation(Vec3.atLowerCornerOf(value));
   }

   public ClientSelectionPart withPendingDelete(boolean value) {
      return copy(this.id, this.source, this.selection, this.blocks, this.transform, value, this.initialBounds, this.initialBlocks, this.transformBaselineFrozen);
   }

   public ClientSelectionPart withSelection(OperationSelectionVolume value) {
      AABB baseline = this.transformBaselineFrozen ? this.initialBounds : worldBounds(value, this.transform);
      return copy(this.id, this.source, value, this.blocks, this.transform, this.pendingDelete, baseline, this.initialBlocks, this.transformBaselineFrozen);
   }

   public ClientSelectionPart withBlocks(Map<BlockPos, ClientBlockSnapshot> value) {
      Map<BlockPos, ClientBlockSnapshot> baselineBlocks = this.transformBaselineFrozen ? this.initialBlocks : value;
      return copy(this.id, this.source, this.selection, value, this.transform, this.pendingDelete, this.initialBounds, baselineBlocks, this.transformBaselineFrozen);
   }

   /** Whether the editable cuboid still occupies its captured position and size. */
   public boolean matchesInitialBounds() {
      if (!this.transformBaselineFrozen) {
         return true;
      }
      AABB current = worldBounds(this.selection, this.transform);
      return current != null && this.initialBounds != null
         && near(current.minX, this.initialBounds.minX)
         && near(current.minY, this.initialBounds.minY)
         && near(current.minZ, this.initialBounds.minZ)
         && near(current.maxX, this.initialBounds.maxX)
         && near(current.maxY, this.initialBounds.maxY)
         && near(current.maxZ, this.initialBounds.maxZ);
   }

   private static ClientSelectionPart copy(
      int id, Source source, OperationSelectionVolume selection,
      Map<BlockPos, ClientBlockSnapshot> blocks, WorkspaceTransform transform,
      boolean pendingDelete, AABB initialBounds,
      Map<BlockPos, ClientBlockSnapshot> initialBlocks, boolean frozen
   ) {
      return new ClientSelectionPart(id, source, selection, blocks, transform, pendingDelete, initialBounds, initialBlocks, frozen);
   }

   /** True once this part has any client-side transform applied to it. */
   public boolean transformed() {
      return this.transform.hasEffect();
   }

   /** Cuboids become OBBs only while their accumulated rotation is non-orthogonal. */
   public boolean axisAlignedCuboid() {
      return this.selection != null
         && this.selection.mode() == io.github.fastformer.fastplace.OperationSelectionMode.CUBOID
         && orthogonal(this.transform.rotation().x)
         && orthogonal(this.transform.rotation().y)
         && orthogonal(this.transform.rotation().z);
   }

   public boolean orientedCuboid() {
      return this.selection != null
         && this.selection.mode() == io.github.fastformer.fastplace.OperationSelectionMode.CUBOID
         && !this.axisAlignedCuboid();
   }

   private static boolean orthogonal(double radians) {
      double quarterTurns = radians / (Math.PI * 0.5);
      return Math.abs(quarterTurns - Math.rint(quarterTurns)) <= ORTHOGONAL_EPSILON;
   }

   private static AABB worldBounds(OperationSelectionVolume selection, WorkspaceTransform transform) {
      if (selection == null || transform == null) {
         return null;
      }
      Vec3 translation = transform.translation();
      return selection.bounds().move(translation.x, translation.y, translation.z);
   }

   private static boolean near(double first, double second) {
      return Math.abs(first - second) <= BOUNDS_EPSILON;
   }

   public enum Source {
      WORLD,
      CLIPBOARD
   }
}
