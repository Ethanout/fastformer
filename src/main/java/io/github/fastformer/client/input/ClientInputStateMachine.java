package io.github.fastformer.client.input;

import java.util.Objects;

/** Owns input routing, cancellation, and the lifetime of a mouse gesture. */
public final class ClientInputStateMachine {
   private State state = State.IDLE;
   private long generation;
   private long gesture;
   private int gestureButton = -1;
   private long pendingRequest;
   private java.util.UUID pendingTransfer;

   public State state() {
      return state;
   }

   public long generation() {
      return generation;
   }

   /** Cancel stays in effect until the session and its recovery task end. */
   public void observe(State observed) {
      Objects.requireNonNull(observed, "observed");
      if (observed == State.CANCELLING || observed == State.SUBMITTING) {
         throw new IllegalArgumentException("Use cancel() to request cancellation");
      }
      if (state == State.SUBMITTING) {
         return;
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

   public boolean submit(long requestId) {
      if (requestId <= 0 || !canSubmit()) {
         return false;
      }
      pendingRequest = requestId;
      pendingTransfer = null;
      transition(State.SUBMITTING);
      return true;
   }

   public boolean submit(java.util.UUID transferId) {
      Objects.requireNonNull(transferId, "transferId");
      if (!canSubmit()) {
         return false;
      }
      pendingRequest = 0;
      pendingTransfer = transferId;
      transition(State.SUBMITTING);
      return true;
   }

   private boolean canSubmit() {
      return state == State.BUILDING || state == State.GEOMETRY || state == State.ADJUSTING;
   }

   public void acknowledge(java.util.UUID transferId, State observed) {
      if (state == State.SUBMITTING && transferId != null && transferId.equals(pendingTransfer)) {
         Objects.requireNonNull(observed, "observed");
         if (observed == State.SUBMITTING || observed == State.CANCELLING) {
            throw new IllegalArgumentException("Acknowledgement requires a server state");
         }
         pendingTransfer = null;
         transition(Objects.requireNonNull(observed, "observed"));
      }
   }

   public void acknowledge(long requestId, State observed) {
      if (state == State.SUBMITTING && requestId > 0 && pendingRequest == requestId) {
         Objects.requireNonNull(observed, "observed");
         if (observed == State.SUBMITTING || observed == State.CANCELLING) {
            throw new IllegalArgumentException("Acknowledgement requires a server state");
         }
         pendingRequest = 0;
         transition(Objects.requireNonNull(observed, "observed"));
      }
   }

   public void abortSubmission(long requestId, State observed) {
      acknowledge(requestId, observed);
   }

   public void abortSubmission(java.util.UUID transferId, State observed) {
      acknowledge(transferId, observed);
   }

   public Dispatch dispatch(InputKind input) {
      Objects.requireNonNull(input, "input");
      if (input == InputKind.CREATE_SELECTION) {
         return switch (state) {
            case SELECTING, ADJUSTING -> Dispatch.OPERATION;
            default -> Dispatch.BLOCKED;
         };
      }
      if (input == InputKind.PASTE_WORKSPACE) {
         return switch (state) {
            case IDLE -> Dispatch.VANILLA;
            case SELECTING, ADJUSTING -> Dispatch.OPERATION;
            default -> Dispatch.BLOCKED;
         };
      }
      if (input == InputKind.CANCEL) {
         return switch (state) {
            case BUILDING, GEOMETRY, SELECTING, ADJUSTING, SUBMITTING, PLACING -> Dispatch.CANCEL;
            case IDLE, RESTORING, CANCELLING -> Dispatch.BLOCKED;
         };
      }
      return switch (state) {
         case IDLE -> Dispatch.VANILLA;
         case BUILDING -> Dispatch.BUILDING;
         case GEOMETRY -> Dispatch.GEOMETRY;
         case SELECTING, ADJUSTING -> Dispatch.OPERATION;
         case SUBMITTING, PLACING, RESTORING, CANCELLING -> Dispatch.BLOCKED;
      };
   }

   public boolean routesToVanilla(InputKind input) {
      return dispatch(input) == Dispatch.VANILLA;
   }

   public long beginGesture() {
      return beginGesture(-1);
   }

   public long beginGesture(int button) {
      if (dispatch(InputKind.POINTER) == Dispatch.BLOCKED) {
         return 0L;
      }
      gesture = ++generation;
      gestureButton = button;
      return gesture;
   }

   public boolean finishGesture(int button, long token) {
      if (!accepts(token) || gestureButton != button) {
         return false;
      }
      gesture = 0L;
      gestureButton = -1;
      return true;
   }

   public boolean accepts(long token) {
      return token != 0L && token == gesture;
   }

   public void reset() {
      state = State.IDLE;
      pendingRequest = 0;
      pendingTransfer = null;
      invalidateGesture();
   }

   private void transition(State next) {
      if (next != state) {
         state = next;
         if (next != State.SUBMITTING) {
            pendingRequest = 0;
            pendingTransfer = null;
         }
         invalidateGesture();
      }
   }

   private void invalidateGesture() {
      generation++;
      gesture = 0L;
      gestureButton = -1;
   }

   public enum State {
      IDLE,
      BUILDING,
      GEOMETRY,
      SELECTING,
      ADJUSTING,
      SUBMITTING,
      PLACING,
      RESTORING,
      CANCELLING
   }

   public enum InputKind {
      KEY,
      POINTER,
      INTERACTION,
      SCROLL,
      CREATE_SELECTION,
      PASTE_WORKSPACE,
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
