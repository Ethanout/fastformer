package io.github.fastformer.server.session;

import com.mojang.authlib.GameProfile;
import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.quickshape.QuickShapeDraft;
import io.github.fastformer.server.player.PlayerStateRegistry;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InteractionModifierGameTests {
   private InteractionModifierGameTests() {}

   @GameTest(template = "fastformergametests.empty")
   public static void queriesDoNotCreateStateAndDetachClearsPhysicalInput(GameTestHelper helper) {
      var player = unattachedPlayer(helper);
      var shapes = QuickShapeSystem.of(player);
      helper.assertFalse(SessionSystem.of(player).modifierHeld(player), "unbound player inherited a modifier");
      SessionSystem.detachPlayer(player);
      helper.assertTrue(PlayerStateRegistry.find(player).isEmpty(), "query or cleanup created player state");
      var draft = new QuickShapeDraft();
      shapes.sessions.activate(player.getUUID(), draft);
      InteractionModifierState.set(player, true);
      draft.setModifierHeld(true);
      try {
         SessionSystem.detachPlayer(player);
         helper.assertFalse(SessionSystem.of(player).modifierHeld(player), "detach retained physical input");
         helper.assertFalse(draft.modifierHeld(), "detach retained the workflow modifier");
         helper.assertTrue(shapes.sessions.active(player.getUUID()) == draft, "detach removed retained work");
         PlayerStateRegistry.detach(player);
         boolean rejected = false;
         try { InteractionModifierState.set(player, true); }
         catch (IllegalStateException expected) { rejected = true; }
         helper.assertTrue(rejected, "late input recreated detached state");
      } finally {
         shapes.sessions.discard(player.getUUID());
         PlayerStateRegistry.detach(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void dimensionChangeClearsActiveAndRestoredDraftModifiers(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var shapes = QuickShapeSystem.of(player);
      var source = new QuickShapeDraft();
      var destination = new QuickShapeDraft();
      shapes.sessions.activate(player.getUUID(), destination);
      shapes.parkDimensionSession(player.getUUID(), Level.OVERWORLD);
      shapes.sessions.activate(player.getUUID(), source);
      source.setModifierHeld(true);
      destination.setModifierHeld(true);
      InteractionModifierState.set(player, true);
      try {
         PlayerStateRegistry.dimensionChanged(player);
         SessionSystem.of(player).handleDimensionChange(player, Level.NETHER, Level.OVERWORLD);
         helper.assertFalse(SessionSystem.of(player).modifierHeld(player), "dimension retained physical input");
         helper.assertFalse(source.modifierHeld(), "parked source retained its old modifier");
         helper.assertFalse(destination.modifierHeld(), "restored draft retained its old modifier");
         helper.assertTrue(shapes.sessions.active(player.getUUID()) == destination, "destination draft was lost");
      } finally {
         shapes.sessions.discard(player.getUUID());
         PlayerStateRegistry.detach(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void replacedPlayersRejectLateModifierCleanup(GameTestHelper helper) {
      var original = unattachedPlayer(helper);
      var shapes = QuickShapeSystem.of(original);
      InteractionModifierState.set(original, true);
      var replacement = new ServerPlayer(original.getServer(), helper.getLevel(), original.getGameProfile(),
         ClientInformation.createDefault());
      PlayerStateRegistry.attach(replacement);
      helper.assertFalse(SessionSystem.of(replacement).modifierHeld(replacement), "replacement inherited old physical input");
      var draft = new QuickShapeDraft();
      shapes.sessions.activate(replacement.getUUID(), draft);
      InteractionModifierState.set(replacement, true);
      draft.setModifierHeld(true);
      try {
         SessionSystem.detachPlayer(original);
         PlayerStateRegistry.detach(original);
         helper.assertTrue(SessionSystem.of(replacement).modifierHeld(replacement), "late cleanup erased replacement input");
         helper.assertTrue(draft.modifierHeld(), "late cleanup erased replacement workflow input");
      } finally {
         shapes.sessions.discard(replacement.getUUID());
         PlayerStateRegistry.detach(replacement);
      }
      helper.succeed();
   }

   private static ServerPlayer unattachedPlayer(GameTestHelper helper) {
      return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
         new GameProfile(UUID.randomUUID(), "modifier-scope"), ClientInformation.createDefault());
   }
}
