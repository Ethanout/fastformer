package io.github.fastformer.common.geometry.generation.face;

import io.github.fastformer.common.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.geometry.generation.source.*;
import io.github.fastformer.common.geometry.generation.face.entry.*;
import io.github.fastformer.common.geometry.generation.volume.*;
import io.github.fastformer.common.geometry.generation.shape.*;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;

import io.github.fastformer.common.geometry.LineTieBias;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.common.geometry.model.FillMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Locks the production raster's coordinates before retiring alternate algorithms. */
class QuadRasterizationBaselineTest {
   @Test
   void productionFaceAndVolumeCoordinatesRemainStable() throws Exception {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      List<List<Vec3>> faces = List.of(
         rectangle(new Vec3(0.5, 0.5, 0.5), new Vec3(6, 0, 0), new Vec3(0, 0, 4)),
         rectangle(new Vec3(-2.5, 3.5, 1.5), new Vec3(6, 2, -1), new Vec3(-2, 5, 3)),
         rectangle(new Vec3(282.5, 63.5, 127.5), new Vec3(-3, 7, 4), new Vec3(-6, 0, -5)),
         rectangle(new Vec3(-0.2, -3.8, 2.1), new Vec3(5.2, 0, 0), new Vec3(0, 0, 4.8)),
         rectangle(new Vec3(4.5, -8.5, -1.5), new Vec3(-8, -3, -6), new Vec3(7, 1, 8)),
         rectangle(Vec3.ZERO, Vec3.ZERO, new Vec3(0, 4, 2))
      );
      for (List<Vec3> face : faces) {
         for (LineTieBias bias : LineTieBias.values()) {
            for (FillMode fill : FillMode.values()) {
               for (int limit : new int[]{1, 10000}) {
                  update(digest, QuadFaceGenerator.generate(face, fill, limit,
                     BlockGenerationObserver.NONE, bias));
                  for (Vec3 extrusion : List.of(new Vec3(2, 5, -3), new Vec3(-1.2, 0.7, 3.8), Vec3.ZERO)) {
                     update(digest, TiltedBoxGenerator.generate(face, extrusion, fill, limit,
                        BlockGenerationObserver.NONE, bias));
                  }
               }
            }
         }
      }
      assertEquals("41faa174f3953aea13a5d37eb2423d7ba4e34be839ca81605f14459de07bff79", HexFormat.of().formatHex(digest.digest()));
   }

   private static List<Vec3> rectangle(Vec3 origin, Vec3 first, Vec3 second) {
      return List.of(origin, origin.add(first), origin.add(first).add(second), origin.add(second));
   }

   private static void update(MessageDigest digest, Set<BlockPos> blocks) {
      String status = GenerationLimitExceeded.is(blocks) ? "limit" : GenerationFailed.is(blocks) ? "failed" : "ok";
      digest.update((status + ":" + blocks.size() + ":").getBytes(StandardCharsets.UTF_8));
      if (status.equals("ok")) {
         blocks.stream().sorted(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)).forEach(block ->
               digest.update((block.getX() + "," + block.getY() + "," + block.getZ() + ";")
                  .getBytes(StandardCharsets.UTF_8)));
      }
   }
}
