package io.github.fastformer.common.geometry.generation.volume;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Independent column-volume shell for face/volume policy comparisons. */
public final class ColumnVolumeFixtures {
   private ColumnVolumeFixtures() {}

   public static Set<BlockPos> shell(List<Vec3> base, Vec3 extrusion, int limit,
      BlockGenerationObserver observer, LineTieBias tie) {
      return BresenhamColumnVolume.generate(base, extrusion, limit, observer, tie).shell();
   }
}
