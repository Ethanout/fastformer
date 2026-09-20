package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.mask.SourceMaskRenderFilter;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The render-only source mask as the ghost preview level sees it.
 *
 * <p>The preview level reads the real level as its base. A masked source position must read
 * as hidden there, or the model builder culls the faces between a ghost block and the source
 * block that the level still holds. The preview blocks themselves stay visible.
 *
 * <p>This runs on the dedicated test server with the real block registry.
 * {@code PreviewBlockOcclusion} and {@code SourceMaskRenderFilter} reference no client-only
 * class, so the server can load them.
 */
@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SourceMaskOcclusionGameTests {
   private static final String BATCH = "source_mask_occlusion";

   private SourceMaskOcclusionGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", batch = BATCH, timeoutTicks = 100)
   public static void theBaseLookupHidesAMaskedSourcePosition(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
      BlockPos neighbour = helper.absolutePos(new BlockPos(2, 1, 1));
      level.setBlock(source, Blocks.STONE.defaultBlockState(), 2);
      level.setBlock(neighbour, Blocks.STONE.defaultBlockState(), 2);

      SourceMaskRenderFilter filter = SourceMaskRenderFilter.instance();
      try {
         filter.publish(Set.of(source));
         BlockGetter preview = PreviewBlockOcclusion.level(level, Map.of());

         helper.assertTrue(
            preview.getBlockState(source).is(Blocks.VOID_AIR),
            "a masked source position still showed its block to the preview level"
         );
         helper.assertTrue(
            preview.getBlockState(neighbour).is(Blocks.STONE),
            "the mask hid an unmasked neighbour position"
         );
      } finally {
         filter.clear();
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = BATCH, timeoutTicks = 100)
   public static void aPreviewBlockKeepsItsOwnStateOnAMaskedPosition(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
      level.setBlock(source, Blocks.STONE.defaultBlockState(), 2);

      SourceMaskRenderFilter filter = SourceMaskRenderFilter.instance();
      try {
         filter.publish(Set.of(source));
         BlockAndTintGetter preview = PreviewBlockOcclusion.level(
            level, Map.of(source, Blocks.GOLD_BLOCK.defaultBlockState())
         );

         helper.assertTrue(
            preview.getBlockState(source).is(Blocks.GOLD_BLOCK),
            "the mask hid the preview block that draws the new target block"
         );
      } finally {
         filter.clear();
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = BATCH, timeoutTicks = 100)
   public static void anUnmaskedPositionKeepsTheBaseState(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos masked = helper.absolutePos(new BlockPos(1, 1, 1));
      BlockPos untouched = helper.absolutePos(new BlockPos(3, 1, 1));
      level.setBlock(untouched, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);

      SourceMaskRenderFilter filter = SourceMaskRenderFilter.instance();
      try {
         filter.publish(Set.of(masked));
         BlockGetter preview = PreviewBlockOcclusion.level(level, Map.of());

         helper.assertTrue(
            preview.getBlockState(untouched).is(Blocks.DIAMOND_BLOCK),
            "the base state of an unmasked position changed"
         );
      } finally {
         filter.clear();
      }
      helper.succeed();
   }
}
