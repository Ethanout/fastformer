package io.github.fastformer.common.geometry.raster;

import com.mojang.serialization.Lifecycle;
import io.github.fastformer.FastFormer;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicies;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicySet;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterRule;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterRules;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FaceRasterRulesGameTests {
   private FaceRasterRulesGameTests() {}

   @GameTest(template = "fastformergametests.empty")
   public static void missingRegistryRetainsOriginalEntranceRules(GameTestHelper helper) {
      helper.assertTrue(FaceRasterRules.capture(new RegistryAccess.ImmutableRegistryAccess(List.of()))
         .equals(FaceRasterPolicySet.DEFAULT), "missing registry changed production face shapes");
      helper.assertTrue(helper.getLevel().registryAccess().registry(FaceRasterRules.REGISTRY).isPresent(),
         "face raster datapack registry was not installed");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void sharedAndEntranceOverridesResolveToOneImmutableSnapshot(GameTestHelper helper) {
      var registry = new MappedRegistry<>(FaceRasterRules.REGISTRY, Lifecycle.stable());
      Registry.register(registry, FaceRasterPolicies.id("default"), new FaceRasterRule(Optional.of(FaceRasterPolicies.CLASSIFIED)));
      Registry.register(registry, FaceRasterPolicies.id("quad"), new FaceRasterRule(Optional.of(FaceRasterPolicies.CURRENT_QUAD)));
      registry.freeze();
      var captured = FaceRasterRules.capture(new RegistryAccess.ImmutableRegistryAccess(List.of(registry)));
      helper.assertTrue(captured.quad().equals(FaceRasterPolicies.CURRENT_QUAD), "quad override lost priority");
      helper.assertTrue(captured.polygon().equals(FaceRasterPolicies.CLASSIFIED), "polygon ignored shared override");
      helper.assertTrue(captured.volume().equals(FaceRasterPolicies.CLASSIFIED), "volume ignored shared override");
      helper.assertTrue(!captured.hash().equals(FaceRasterPolicySet.DEFAULT.hash()), "override did not change snapshot hash");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void invalidSharedRuleFallsBackForEachEntrance(GameTestHelper helper) {
      var registry = new MappedRegistry<>(FaceRasterRules.REGISTRY, Lifecycle.stable());
      Registry.register(registry, FaceRasterPolicies.id("default"), new FaceRasterRule(Optional.empty()));
      registry.freeze();
      var captured = FaceRasterRules.capture(new RegistryAccess.ImmutableRegistryAccess(List.of(registry)));
      helper.assertTrue(captured.equals(FaceRasterPolicySet.DEFAULT), "invalid data unified the original entrance shapes");
      helper.succeed();
   }
}
