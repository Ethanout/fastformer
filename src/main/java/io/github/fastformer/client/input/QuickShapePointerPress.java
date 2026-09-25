package io.github.fastformer.client.input;

import io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot;
import net.minecraft.world.phys.Vec3;
import java.util.Objects;

record QuickShapePointerPress(QuickShapeSubmissionSnapshot draft, PathClosePress path,
   Vec3 eye, Vec3 view, boolean modifierHeld, boolean middle) {
   QuickShapePointerPress {
      Objects.requireNonNull(draft);
      Objects.requireNonNull(path);
      Objects.requireNonNull(eye);
      Objects.requireNonNull(view);
   }

   boolean matches(ClientInputStateMachine routing, QuickShapeSubmissionSnapshot current) {
      return routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.BUILDING
         && draft.equals(current);
   }
}
