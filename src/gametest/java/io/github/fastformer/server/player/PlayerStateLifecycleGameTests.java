package io.github.fastformer.server.player;

import io.github.fastformer.server.history.HistoryTestAccess;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.replace.QuickReplaceSystem;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlayerStateLifecycleGameTests {
   private PlayerStateLifecycleGameTests() {}

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 20)
   public static void detachDiscardsEveryRegisteredFeature(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var first = PlayerStateRegistry.key(ResourceLocation.parse("fastformer:test_first"), State::new);
      var second = PlayerStateRegistry.key(ResourceLocation.parse("fastformer:test_second"), State::new);
      var registry = PlayerStateRegistry.of(player);
      State a = registry.get(first);
      State b = registry.get(second);
      PlayerStateRegistry.dimensionChanged(player);
      helper.assertTrue(a.dimensions == 1 && b.dimensions == 1, "dimension boundary skipped a feature");
      PlayerStateRegistry.detach(player);
      helper.assertTrue(a.detaches == 1 && b.detaches == 1, "detach skipped a feature");
      helper.assertTrue(a.discards == 1 && b.discards == 1, "discard skipped a feature");
      helper.assertTrue(PlayerStateRegistry.find(player).isEmpty(), "detached player remains registered");
      helper.assertFalse(PlayerStateRegistry.accepts(player), "detached player remains admitted");
      boolean closed = false;
      try { registry.get(first); }
      catch (IllegalStateException expected) { closed = true; }
      helper.assertTrue(closed, "old state can be resurrected");
      boolean detached = false;
      try { PlayerStateRegistry.of(player); }
      catch (IllegalStateException expected) { detached = true; }
      helper.assertTrue(detached, "late callback recreated a detached scope");
      PlayerStateRegistry.attach(player);
      helper.assertTrue(PlayerStateRegistry.accepts(player), "reattached player is not admitted");
      helper.assertTrue(PlayerStateRegistry.of(player) != registry, "reattach reused closed state");
      PlayerStateRegistry.detach(player);
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 20, batch = "quick_replace_scope")
   public static void replacementEndsAtDimensionAndConnectionBoundaries(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000);
      helper.assertTrue(QuickReplaceSystem.setActive(player, true), "replacement did not activate");
      PlayerStateRegistry.dimensionChanged(player);
      helper.assertFalse(QuickReplaceSystem.active(player), "replacement leaked across a dimension boundary");
      helper.assertTrue(QuickReplaceSystem.setActive(player, true), "replacement did not restart");
      PlayerStateRegistry.detach(player);
      helper.assertFalse(QuickReplaceSystem.active(player), "replacement remained after detach");
      PlayerStateRegistry.attach(player);
      helper.assertFalse(QuickReplaceSystem.active(player), "reattach inherited replacement state");
      PlayerStateRegistry.detach(player);
      helper.succeed();
   }

   private static final class State implements Scoped {
      int detaches;
      int discards;
      int dimensions;
      @Override public void onDetach() { detaches++; }
      @Override public void onDimensionChanged() { dimensions++; }
      @Override public void onDiscard() { discards++; }
   }
}
