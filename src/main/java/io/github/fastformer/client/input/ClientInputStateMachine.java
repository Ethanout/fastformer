package io.github.fastformer.client.input;

import java.util.Objects;

/** Owns input routing, cancellation, and the lifetime of a mouse gesture. */
public final class ClientInputStateMachine {
   private State state = State.IDLE;
   private long generation;
   private long gesture;

   public State state() {
      return state;
   }

   public long generation() {
      return generation;
   }

   /** Cancel stays in effect until the session and its recovery task end. */
   public void observe(State observed) {
      Objects.requireNonNull(observed, "observed");
      if (observed == State.CANCELLING) {
         throw new IllegalArgumentException("Use cancel() to request cancellation");
      }
      if (state == State.CANCELLING && observed != State.IDLE) {
         return;
      }
      transition(observed);
   }

   public boolean cancel() {
      if (dispatch(InputKind.CANCEL) != Dispatch.CANCEL) {
         return false;
      }
      transition(State.CANCELLING);
      return true;
   }

   public Dispatch dispatch(InputKind input) {
      Objects.requireNonNull(input, "input");
      if (input == InputKind.CANCEL) {
         return switch (state) {
            case BUILDING, GEOMETRY, SELECTING, ADJUSTING, PLACING -> Dispatch.CANCEL;
            case IDLE, RESTORING, CANCELLING -> Dispatch.BLOCKED;
         };
      }
      return switch (state) {
         case IDLE -> Dispatch.VANILLA;
         case BUILDING -> Dispatch.BUILDING;
         case GEOMETRY -> Dispatch.GEOMETRY;
         case SELECTING, ADJUSTING -> Dispatch.OPERATION;
         case PLACING, RESTORING, CANCELLING -> Dispatch.BLOCKED;
      };
   }

   public long beginGesture() {
      if (dispatch(InputKind.POINTER) == Dispatch.BLOCKED) {
         return 0L;
      }
      gesture = ++generation;
      return gesture;
   }

   public boolean accepts(long token) {
      return token != 0L && token == gesture;
   }

   public void reset() {
      state = State.IDLE;
      invalidateGesture();
   }

   private void transition(State next) {
      if (next != state) {
         state = next;
         invalidateGesture();
      }
   }

   private void invalidateGesture() {
      generation++;
      gesture = 0L;
   }

   public enum State {
      IDLE,
      BUILDING,
      GEOMETRY,
      SELECTING,
      ADJUSTING,
      PLACING,
      RESTORING,
      CANCELLING
   }

   public enum InputKind {
      KEY,
      POINTER,
      INTERACTION,
      SCROLL,
      CANCEL
   }

   public enum Dispatch {
      BLOCKED,
      CANCEL,
      VANILLA,
      BUILDING,
      GEOMETRY,
      OPERATION
   }
}
