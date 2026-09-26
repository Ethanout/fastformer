package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Pairs physical releases with the exact queued press that owns a geometry drag. */
final class GeometryGizmoCapture {
   sealed interface Event {
      record Press(long epoch, long revision, UUID draftId, OperationCallbackScope scope, GeometryMode mode,
         GeometryGizmoDrag drag) implements Event { }
      record Release(Press press, Vec3 eye, Vec3 view, boolean controlDown) implements Event { }
   }

   private long epoch;
   private Event.Press physicalPress;
   private Event.Press activePress;
   private long activeToken;

   Event.Press press(long revision, UUID draftId, OperationCallbackScope scope, GeometryMode mode, GeometryGizmoDrag drag) {
      if (physicalPress != null) return null;
      physicalPress = new Event.Press(epoch, revision, draftId, scope, mode, drag);
      return physicalPress;
   }

   Event.Release release(int button, Vec3 eye, Vec3 view, boolean controlDown) {
      if (physicalPress == null || physicalPress.drag().mouseButton() != button) return null;
      var release = new Event.Release(physicalPress, eye, view, controlDown);
      physicalPress = null;
      return release;
   }

   boolean captured() {
      return physicalPress != null || activePress != null;
   }

   boolean hasPhysicalPress() {
      return physicalPress != null;
   }

   boolean ownsPhysicalButton(int button) {
      return physicalPress != null && physicalPress.drag().mouseButton() == button;
   }

   boolean ownsInteractionButton(int button) {
      return ownsPhysicalButton(button) || activePress != null && activePress.drag().mouseButton() == button;
   }

   boolean accepts(Event.Press press) {
      return press.epoch() == epoch;
   }

   void activate(Event.Press press, long token) {
      if (!accepts(press) || press.draftId() == null || token == 0) return;
      activePress = press;
      activeToken = token;
   }

   boolean owns(Event.Release release, long token) {
      return activePress == release.press() && activeToken != 0 && activeToken == token;
   }

   boolean owns(long token) {
      return activePress != null && activeToken != 0 && activeToken == token;
   }

   Event.Press activePress() {
      return activePress;
   }

   boolean matchesActive(UUID draftId, OperationCallbackScope scope, GeometryMode mode) {
      return activePress != null && activePress.draftId() != null && activePress.draftId().equals(draftId)
         && activePress.scope().equals(scope) && activePress.mode() == mode;
   }

   void finish(Event.Release release) {
      if (activePress != release.press()) return;
      activePress = null;
      activeToken = 0;
   }

   void cancel() {
      epoch++;
      physicalPress = null;
      activePress = null;
      activeToken = 0;
   }
}
