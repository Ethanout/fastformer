package io.github.fastformer.server.session;

import io.github.fastformer.FastFormer;
import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.common.quickshape.QuickShapeDraft;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.geometry.session.GeometrySession;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.common.selection.session.OperationSession;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.server.task.block.PlacementTask;
import io.github.fastformer.server.task.block.PlacementTaskPlan;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SessionBoundaryGameTests {
   private SessionBoundaryGameTests() {}

   @GameTest(template = "fastformergametests.empty")
   public static void everyGeometryModeRoutesCapturedPointsToItsOwnDraft(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var system = SessionSystem.of(player);
      try {
         for (GeometryMode mode : GeometryMode.values()) {
            var draft = new GeometrySession();
            draft.setMode(mode);
            helper.assertTrue(system.modes().geometry(mode).open(player, draft) == draft, "open replaced typed draft");
            var registered = system.modes().active(player).orElseThrow();
            helper.assertTrue(registered.id().equals(BuiltinModes.geometry(mode)),
               "wrong geometry registration for " + mode);
            BlockPos point = helper.absolutePos(new BlockPos(2, 2, 2));
            Vec3 location = Vec3.atCenterOf(point);
            var hit = new BlockHitResult(location, Direction.UP, point, false);
            helper.assertTrue(system.modes().geometryPoint(player, hit, player.getEyePosition(), player.getViewVector(1.0F)),
               "registered geometry rejected point for " + mode);
            helper.assertTrue(!draft.points().isEmpty(), "point did not reach typed geometry draft for " + mode);
            system.cancelEditing(player);
         }
      } finally {
         system.discardPlayer(player);
         PlayerStateRegistry.detach(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void cycleUsesSelectionPriorityButConfirmKeepsGeometryPriority(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var system = SessionSystem.of(player);
      var selection = new OperationSession(OperationSelectionMode.PRISM, 10);
      var geometry = new GeometrySession();
      var shape = new QuickShapeDraft();
      shape.addPoint(helper.absolutePos(new BlockPos(2, 2, 2)), Vec3.ZERO, new Vec3(0, 0, 1));
      system.modes().quickShape().open(player, shape);
      system.modes().selection().open(player, selection);
      system.modes().geometry(geometry.mode()).open(player, geometry);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.STONE));
      try {
         var previousMode = selection.selectionMode();
         helper.assertTrue(system.modes().cycle(player, null).handled(), "selection cycle not handled");
         helper.assertTrue(selection.selectionMode() != previousMode, "cycle did not use selection priority");
         helper.assertFalse(system.modes().confirm(player).handled(), "incomplete geometry unexpectedly confirmed");
         helper.assertTrue(system.quickShape.session(player).orElse(null) == shape, "confirm incorrectly reached quick shape");
         helper.assertFalse(system.placementTasks().active(player), "blocked geometry confirm scheduled lower-priority placement");
         helper.assertFalse(system.modes().allowsCommandAdjust(player), "command adjust bypassed an incomplete active editor");
      } finally {
         system.placementTasks().cancel(player);
         system.discardPlayer(player);
         PlayerStateRegistry.detach(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void cancelEditingRetainsParkedWorkAndQueuedTask(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var system = SessionSystem.of(player);
      var owner = player.getUUID();
      var parked = new OperationSession(OperationSelectionMode.CUBOID, 10);
      var task = PlacementTask.ready(Set.of(BlockPos.ZERO), new PlacementTaskPlan(
         Blocks.STONE.defaultBlockState(), null, OperationConflictMode.REPLACE,
         PlacementUpdateMode.CLIENT_ONLY, 10, player.level().dimension()));
      system.operation.sessions.activate(owner, parked);
      system.operation.parkDimensionSession(owner, Level.NETHER);
      system.quickShape.sessions.activate(owner, new QuickShapeDraft());
      system.operation.sessions.activate(owner, new OperationSession(OperationSelectionMode.CUBOID, 10));
      system.geometry.sessions.activate(owner, new GeometrySession());
      system.placementTasks().admit(owner, task);
      try {
         system.cancelEditing(player);
         helper.assertFalse(system.quickShape.active(player), "quick shape remained active");
         helper.assertFalse(system.operation.active(player), "selection remained active");
         helper.assertFalse(system.geometry.active(player), "geometry remained active");
         helper.assertTrue(system.operation.sessions.parked(owner, Level.NETHER) == parked, "cancel discarded parked work");
         helper.assertTrue(system.placementTasks().isCurrent(owner, task), "cancel removed queued world work");
      } finally {
         system.placementTasks().cancel(player);
         system.discardPlayer(player);
         PlayerStateRegistry.detach(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void dimensionBoundaryParksAndRestoresEveryEditor(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var system = SessionSystem.of(player);
      var owner = player.getUUID();
      var source = player.level().dimension();
      var shape = new QuickShapeDraft();
      var selection = new OperationSession(OperationSelectionMode.CUBOID, 10);
      var geometry = new GeometrySession();
      system.modes().quickShape().open(player, shape);
      system.modes().selection().open(player, selection);
      system.modes().geometry(geometry.mode()).open(player, geometry);
      system.setModifierHeld(player, true);
      try {
         system.handleDimensionChange(player, source, Level.NETHER);
         helper.assertFalse(system.quickShape.active(player), "source shape stayed active in destination");
         helper.assertFalse(system.operation.active(player), "source selection stayed active in destination");
         helper.assertFalse(system.geometry.active(player), "source geometry stayed active in destination");
         helper.assertFalse(system.modifierHeld(player), "dimension retained physical modifier");
         helper.assertFalse(shape.modifierHeld(), "parked draft retained its modifier");
         system.handleDimensionChange(player, Level.NETHER, source);
         helper.assertTrue(system.quickShape.sessions.active(owner) == shape, "source shape was not restored");
         helper.assertTrue(system.operation.sessions.active(owner) == selection, "source selection was not restored");
         helper.assertTrue(system.geometry.sessions.active(owner) == geometry, "source geometry was not restored");
      } finally {
         system.discardPlayer(player);
         PlayerStateRegistry.detach(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void detachRetainsEveryTypedDraftAndItsParkedDimension(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var system = SessionSystem.of(player);
      var owner = player.getUUID();
      var shape = new QuickShapeDraft();
      var selection = new OperationSession(OperationSelectionMode.CUBOID, 10);
      var geometry = new GeometrySession();
      PlayerStateRegistry.attach(player);
      try {
         system.modes().quickShape().open(player, shape);
         system.modes().selection().open(player, selection);
         system.modes().geometry(geometry.mode()).open(player, geometry);
         system.handleDimensionChange(player, player.level().dimension(), Level.NETHER);
         var parkedShape = new QuickShapeDraft();
         system.modes().quickShape().open(player, parkedShape);
         SessionSystem.detachPlayer(player);
         helper.assertTrue(system.modes().quickShape().session(player).orElseThrow() == parkedShape, "detach removed current draft");
         helper.assertTrue(system.quickShape.sessions.parked(owner, player.level().dimension()) == shape, "detach removed parked quick shape");
         helper.assertTrue(system.operation.sessions.parked(owner, player.level().dimension()) == selection, "detach removed parked selection");
         helper.assertTrue(system.geometry.sessions.parked(owner, player.level().dimension()) == geometry, "detach removed parked geometry");
         system.handleDimensionChange(player, Level.NETHER, player.level().dimension());
         helper.assertTrue(system.modes().quickShape().session(player).orElseThrow() == shape, "rebind replaced quick shape");
         helper.assertTrue(system.modes().selection().session(player).orElseThrow() == selection, "rebind replaced selection");
         helper.assertTrue(system.modes().geometry(geometry.mode()).session(player).orElseThrow() == geometry, "rebind replaced geometry");
      } finally {
         system.discardPlayer(player);
         PlayerStateRegistry.detach(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void activeEditorsKeepPriorityOverHeldItemClassification(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      var system = SessionSystem.of(player);
      var owner = player.getUUID();
      try {
         player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.STONE));
         helper.assertTrue(system.classify(player) == InteractionState.BUILDING, "placeable item lost idle classification");
         system.quickShape.sessions.activate(owner, new QuickShapeDraft());
         player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND));
         helper.assertTrue(system.classify(player) == InteractionState.BUILDING, "held item overrode active shape");
         system.geometry.sessions.activate(owner, new GeometrySession());
         helper.assertTrue(system.classify(player) == InteractionState.GEOMETRY, "geometry lost active priority");
         system.operation.sessions.activate(owner, new OperationSession(OperationSelectionMode.CUBOID, 10));
         helper.assertTrue(system.classify(player) == InteractionState.OPERATION, "selection lost active priority");
         system.cancelEditing(player);
         helper.assertTrue(system.classify(player) == InteractionState.VANILLA, "inactive editors overrode vanilla item");
         player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DEBUG_STICK));
         helper.assertTrue(system.classify(player) == InteractionState.SPECIAL_ITEM, "special item lost idle classification");
         player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
         helper.assertTrue(system.classify(player) == InteractionState.OPERATION, "empty hand lost selection classification");
      } finally {
         system.discardPlayer(player);
         PlayerStateRegistry.detach(player);
      }
      helper.succeed();
   }
}
