package io.github.fastformer.client.input;

/**
 * Client-only lifetime guard for pointer gestures.  A new gesture invalidates
 * the previous token; cancelling a session therefore also invalidates any
 * mouse-release or tick callback that was queued for the old gesture.
 */
public final class PointerGestureState {
   private long nextToken;
   private long activeToken;
   private Kind kind = Kind.NONE;

   public long begin(Kind kind) {
      if (kind == null || kind == Kind.NONE) {
         throw new IllegalArgumentException("A concrete pointer gesture kind is required");
      }
      this.activeToken = ++this.nextToken;
      this.kind = kind;
      return this.activeToken;
   }

   public boolean owns(long token, Kind expected) {
      return token != 0L && token == this.activeToken && expected == this.kind;
   }

   public boolean active(Kind expected) {
      return expected != null && expected == this.kind;
   }

   public void finish(long token) {
      if (token == this.activeToken) {
         this.kind = Kind.NONE;
         this.activeToken = 0L;
      }
   }

   public void cancel() {
      this.activeToken = ++this.nextToken;
      this.kind = Kind.NONE;
   }

   public long activeToken() {
      return this.activeToken;
   }

   public Kind kind() {
      return this.kind;
   }

   public enum Kind {
      NONE,
      BUILDING_GEOMETRY,
      OPERATION_FACE,
      OPERATION_POINT,
      OPERATION_GIZMO,
      WORKSPACE_FACE,
      WORKSPACE_GIZMO
   }
}
