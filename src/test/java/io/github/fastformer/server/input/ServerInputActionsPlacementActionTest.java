package io.github.fastformer.server.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerInputActionsPlacementActionTest {
   private final ServerInputState state = new ServerInputState();

   @Test
   void acceptsOnlyStrictlyIncreasingRequestIds() {
      assertTrue(state.placementActions.accept(1L));
      assertFalse(state.placementActions.accept(1L));
      assertFalse(state.placementActions.accept(0L));
      assertFalse(state.placementActions.accept(0L));
      assertTrue(state.placementActions.accept(2L));
      assertFalse(state.placementActions.accept(1L));
   }

   @Test
   void operationAndPlacementRequestsShareTheDuplicateGate() {
      assertTrue(state.placementActions.accept(12L));
      assertFalse(state.placementActions.accept(12L));
      assertFalse(state.placementActions.accept(11L));
      assertTrue(state.placementActions.accept(13L));
   }

   @Test
   void clearingAnOwnerAllowsAFreshConnectionSequence() {
      assertTrue(state.placementActions.accept(8L));
      state.onDetach();
      assertTrue(state.placementActions.accept(1L));
   }

   @Test
   void dimensionBoundaryClearsAllInputChannelsAndOtherPlayersAreIndependent() {
      var other = new ServerInputState();
      assertTrue(state.placementActions.accept(8L));
      assertTrue(state.geometryActions.accept(8L));
      assertTrue(state.geometryDragActions.accept(8L));
      assertTrue(state.pointCommands.accept(8L));
      assertTrue(state.extendActions.accept(8L));
      assertTrue(state.transformActions.accept(8L));
      assertTrue(other.placementActions.accept(1L));
      state.onDimensionChanged();
      assertTrue(state.placementActions.accept(1L));
      assertTrue(state.geometryActions.accept(1L));
      assertTrue(state.geometryDragActions.accept(1L));
      assertTrue(state.pointCommands.accept(1L));
      assertTrue(state.extendActions.accept(1L));
      assertTrue(state.transformActions.accept(1L));
      assertFalse(other.placementActions.accept(1L));
   }
}
