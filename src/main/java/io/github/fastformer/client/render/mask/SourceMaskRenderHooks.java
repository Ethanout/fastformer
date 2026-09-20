package io.github.fastformer.client.render.mask;

import io.github.fastformer.client.mixin.LevelRendererAccessor;
import io.github.fastformer.client.render.mask.SourceMaskRenderFilter.Snapshot;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Client-thread glue that turns a mask change into a section rebuild.
 *
 * <p>The mask itself is render-only, so the client level keeps the real block. The renderer
 * learns about a change through dirty marks: {@link #reconcile} marks the sections of every
 * position that entered or left the mask.
 *
 * <p>{@link LevelRenderer#setSectionDirty} rebuilds one section. {@link #touchedSections}
 * adds the sections across a section boundary, because a neighbour block in the next section
 * changes its mesh.
 *
 * <p>Call {@link #reconcile} once per client tick, from the client thread.
 */
public final class SourceMaskRenderHooks {
   private static long markedRevision = -1L;
   private static Set<BlockPos> markedPositions = Set.of();
   private static ClientLevel markedLevel;

   private SourceMaskRenderHooks() {
   }

   /** Marks the sections of every position that entered or left the mask as dirty. */
   public static void reconcile() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null) {
         return;
      }

      ClientLevel level = minecraft.level;
      if (level != markedLevel) {
         // A new world builds every section from scratch, so old positions need no rebuild.
         markedLevel = level;
         markedRevision = -1L;
         markedPositions = Set.of();
      }

      if (level == null) {
         return;
      }

      LevelRenderer renderer = minecraft.levelRenderer;
      if (!(renderer instanceof LevelRendererAccessor accessor) || accessor.fastformer$viewArea() == null) {
         // No section grid exists yet: before the first level, and during a world switch.
         // The first build of every section already reads the current mask.
         return;
      }

      Snapshot current = SourceMaskRenderFilter.instance().snapshot();
      if (current.revision() == markedRevision) {
         return;
      }

      Set<BlockPos> changed = difference(markedPositions, current.positions());
      changed.addAll(difference(current.positions(), markedPositions));

      for (long section : touchedSections(changed)) {
         renderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
      }

      // Commit the bookkeeping only after every dirty mark landed. A repeated mark is
      // idempotent, so a failure above must retry the same difference on the next tick
      // instead of returning early with the redraw lost.
      markedRevision = current.revision();
      markedPositions = current.positions();
   }

   /**
    * Returns the section coordinates that one position affects, including the sections
    * across a section boundary. A neighbour block in the next section changes its mesh, so
    * that section must rebuild as well.
    */
   private static Set<Long> touchedSections(Set<BlockPos> positions) {
      Set<Long> sections = new HashSet<>();
      for (BlockPos pos : positions) {
         int minX = SectionPos.blockToSectionCoord(pos.getX() - 1);
         int maxX = SectionPos.blockToSectionCoord(pos.getX() + 1);
         int minY = SectionPos.blockToSectionCoord(pos.getY() - 1);
         int maxY = SectionPos.blockToSectionCoord(pos.getY() + 1);
         int minZ = SectionPos.blockToSectionCoord(pos.getZ() - 1);
         int maxZ = SectionPos.blockToSectionCoord(pos.getZ() + 1);
         for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
               for (int z = minZ; z <= maxZ; z++) {
                  sections.add(SectionPos.asLong(x, y, z));
               }
            }
         }
      }

      return sections;
   }

   private static Set<BlockPos> difference(Set<BlockPos> from, Set<BlockPos> without) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (BlockPos pos : from) {
         if (!without.contains(pos)) {
            result.add(pos);
         }
      }

      return result;
   }
}
