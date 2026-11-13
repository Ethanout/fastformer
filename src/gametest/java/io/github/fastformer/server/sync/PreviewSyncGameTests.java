package io.github.fastformer.server.sync;

import com.mojang.authlib.GameProfile;
import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.session.FastPlaceActivity;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.server.session.QuickShapeSystem;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreviewSyncGameTests {
   private PreviewSyncGameTests() {}

   @GameTest(template = "fastformergametests.empty")
   public static void queriesAndLateCleanupDoNotCreatePlayerState(GameTestHelper helper) {
      var player = unattachedPlayer(helper);
      assertNoPublications(helper, player);
      PreviewSync.forgetActivity(player);
      helper.assertTrue(PlayerStateRegistry.find(player).isEmpty(), "query or cleanup created a player scope");
      PreviewSync.beginClientSession(player);
      PreviewSync.callbackScope(player);
      PlayerStateRegistry.detach(player);
      assertNoPublications(helper, player);
      PreviewSync.forgetActivity(player);
      boolean rejected = false;
      try { PreviewSync.callbackScope(player); }
      catch (IllegalStateException expected) { rejected = true; }
      helper.assertTrue(rejected, "late publication recreated a detached player");
      helper.assertTrue(PlayerStateRegistry.find(player).isEmpty(), "late cleanup recreated detached scope");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void dimensionAndDetachClearEveryPublication(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var state = PreviewSyncState.of(player);
      var oldScope = PreviewSync.callbackScope(player);
      state.buildingRevision = 7;
      state.operationRevision = 8;
      state.geometryRevision = 9;
      state.activityRevision = 10;
      state.lastActivity = FastPlaceActivity.BUILDING_SESSION;
      PlayerStateRegistry.dimensionChanged(player);
      assertNoPublications(helper, player);
      helper.assertTrue(state.activityRevision == 0 && state.lastActivity == null, "dimension retained activity state");
      helper.assertFalse(oldScope.equals(PreviewSync.callbackScope(player)), "dimension retained the old callback scope");
      state.buildingRevision = 11;
      state.lastActivity = FastPlaceActivity.OPERATION_SESSION;
      PlayerStateRegistry.detach(player);
      helper.assertTrue(state.buildingRevision == 0 && state.lastActivity == null, "discard retained publication state");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void replacementWithSameUuidRejectsOldInstanceCleanup(GameTestHelper helper) {
      var original = unattachedPlayer(helper);
      var previousScope = PreviewSync.callbackScope(original);
      PreviewSyncState.of(original).buildingRevision = 7;
      var replacement = new ServerPlayer(original.getServer(), helper.getLevel(), original.getGameProfile(),
         ClientInformation.createDefault());
      assertNoPublications(helper, replacement);
      PlayerStateRegistry.attach(replacement);
      var currentScope = PreviewSync.callbackScope(replacement);
      helper.assertFalse(previousScope.equals(currentScope), "replacement inherited the previous connection identity");
      PreviewSyncState.of(replacement).buildingRevision = 9;
      PreviewSync.forgetActivity(original);
      helper.assertTrue(PreviewSync.buildingRevision(replacement) == 9, "old cleanup erased replacement revision");
      helper.assertTrue(currentScope.equals(PreviewSync.callbackScope(replacement)), "old cleanup erased replacement scope");
      PlayerStateRegistry.detach(replacement);
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "preview_dimension")
   public static void dimensionEventPublishesAfterInvalidation(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.GOLD_BLOCK));
      var dimension = player.level().dimension();
      BlockPos first = helper.absolutePos(new BlockPos(1, 3, 1));
      try {
         QuickShapeSystem.of(player).addPoint(player, first, first);
         var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
         // Park the destination workflow, then simulate its arrival event with the player already in that dimension.
         QuickShapeSystem.of(player).handleDimensionChange(player, dimension, Level.NETHER);
         var previousScope = PreviewSync.callbackScope(player);
         NeoForge.EVENT_BUS.post(new PlayerChangedDimensionEvent(player, Level.NETHER, dimension));
         helper.assertTrue(QuickShapeSystem.of(player).session(player).orElseThrow() == draft, "dimension lost the parked workflow");
         helper.assertTrue(PreviewSync.buildingRevision(player) > 0, "dimension erased the newly published revision");
         helper.assertTrue(PreviewSync.buildingSubmissionParametersMatch(player), "dimension erased the submission policy");
         helper.assertFalse(previousScope.equals(PreviewSync.callbackScope(player)), "dimension reused the previous callback scope");
      } finally {
         QuickShapeSystem.of(player).remove(player);
         PlayerStateRegistry.detach(player);
      }
      helper.succeed();
   }

   private static void assertNoPublications(GameTestHelper helper, ServerPlayer player) {
      helper.assertTrue(PreviewSync.buildingRevision(player) == 0, "unexpected building revision");
      helper.assertTrue(PreviewSync.geometryRevision(player) == 0, "unexpected geometry revision");
      helper.assertTrue(PreviewSync.operationRevision(player) == 0, "unexpected operation revision");
      helper.assertFalse(PreviewSync.buildingSubmissionParametersMatch(player), "unexpected submission policy");
   }

   private static ServerPlayer unattachedPlayer(GameTestHelper helper) {
      return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
         new GameProfile(UUID.randomUUID(), "preview-scope"), ClientInformation.createDefault());
   }
}
