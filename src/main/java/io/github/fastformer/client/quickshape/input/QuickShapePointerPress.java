package io.github.fastformer.client.quickshape.input;

import io.github.fastformer.client.input.gesture.PathClosePress;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

public record QuickShapePointerPress(QuickShapeSubmissionSnapshot draft, PathClosePress path,
   Vec3 eye, Vec3 view, boolean modifierHeld, boolean middle) {
   public QuickShapePointerPress {
      Objects.requireNonNull(draft);
      Objects.requireNonNull(path);
      Objects.requireNonNull(eye);
      Objects.requireNonNull(view);
   }

   public boolean matches(ClientInputStateMachine routing, QuickShapeSubmissionSnapshot current) {
      return routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.BUILDING
         && draft.equals(current);
   }
}
