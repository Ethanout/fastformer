package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseDragReleaseSemantics;

record PointerReleaseSnapshot(int button, long occurredAtNanos, long clickToken,
   long pointerToken, MouseDragReleaseSemantics.Target target) {
   boolean dispatch(ClientInputSession session, MouseDragReleaseSemantics.Target currentTarget,
      Runnable finish) {
      if (target == MouseDragReleaseSemantics.Target.NONE || target != currentTarget
         || session.clickGestureToken != clickToken || session.pointerGestureToken != pointerToken
         || !session.routing.finishGesture(button, clickToken)) {
         return false;
      }
      session.clickGestureToken = 0L;
      finish.run();
      return true;
   }
}
