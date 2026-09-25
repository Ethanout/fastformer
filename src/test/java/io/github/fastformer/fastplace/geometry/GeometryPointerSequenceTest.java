package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class GeometryPointerSequenceTest {
   private static final OperationCallbackScope SCOPE = new OperationCallbackScope(
      UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()
   );

   @Test
   void pointThenCloseCanUseOneCapturedRevision() {
      GeometrySession session = new GeometrySession();
      GeometryPointerSequence sequence = new GeometryPointerSequence(session, SCOPE, 7L);

      assertTrue(sequence.accepts(session, SCOPE, 7L, 7L));
      sequence.advance(8L);
      assertTrue(sequence.accepts(session, SCOPE, 7L, 8L));
   }

   @Test
   void sequenceRejectsAChangedRevisionOrSession() {
      GeometrySession session = new GeometrySession();
      GeometryPointerSequence sequence = new GeometryPointerSequence(session, SCOPE, 7L);
      sequence.advance(8L);

      assertFalse(sequence.accepts(session, SCOPE, 7L, 9L));
      assertFalse(sequence.accepts(new GeometrySession(), SCOPE, 7L, 8L));
      assertFalse(sequence.accepts(session, new OperationCallbackScope(
         SCOPE.playerId(), SCOPE.dimension(), UUID.randomUUID()
      ), 7L, 8L));
   }

   @Test
   void rebuildingTheSameSessionInvalidatesItsSequence() {
      GeometrySession session = new GeometrySession();
      GeometryPointerSequence sequence = new GeometryPointerSequence(session, SCOPE, 7L);
      session.setMode(session.mode());

      assertFalse(sequence.accepts(session, SCOPE, 7L, 7L));
   }
}
