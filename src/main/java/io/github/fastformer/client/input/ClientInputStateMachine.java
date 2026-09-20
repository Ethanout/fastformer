package io.github.fastformer.client.input;

import java.util.Objects;

/** Owns input routing, cancellation, and the lifetime of a mouse gesture. */
public final class ClientInputStateMachine {
   private State state = State.IDLE;
   private long generation;
   private long gesture;
   private int gestureButton = -1;
   private State submissionOrigin;
   private final ClientRequestTracker requests = new ClientRequestTracker();

   public State state() {
      return state;
   }

   public long generation() {
      return generation;
   }

   /** Cancel stays in effect until the session and its recovery task end. */
   public void observe(State observed) {
      onEvent(new ClientSemanticEvent.Observe(observed));
   }

   public boolean cancel() {
      return onEvent(ClientSemanticEvent.Cancel.INSTANCE) instanceof InteractionTransition.Switch;
   }

   public boolean submit(long requestId) {
      return onEvent(new ClientSemanticEvent.Submit.Placement(requestId)) instanceof InteractionTransition.Switch;
   }

   public boolean submit(java.util.UUID transferId) {
      return onEvent(new ClientSemanticEvent.Submit.Workspace(transferId)) instanceof InteractionTransition.Switch;
   }

   public boolean completeSubmission(java.util.UUID transferId, SubmissionEvent event, State observed) {
      Objects.requireNonNull(event, "event");
      return transferId != null && onEvent(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(transferId), event, observed
      )) instanceof InteractionTransition.Switch;
   }

   public boolean completeSubmission(long requestId, SubmissionEvent event, State observed) {
      return onEvent(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(requestId), event, observed
      )) instanceof InteractionTransition.Switch;
   }

   /** Returns a decision without changing state, gestures, or request ownership. */
   public InteractionTransition inspect(ClientSemanticEvent event) {
      return this.state.onEvent(Objects.requireNonNull(event, "event"), this.requests.current(), this.submissionOrigin);
   }

   /** The client owner calls this once for each delivered event. */
   public InteractionTransition onEvent(ClientSemanticEvent event) {
      InteractionTransition result = inspect(event);
      if (result instanceof InteractionTransition.Switch change) {
         apply(change);
      }
      return result;
   }

   public Dispatch dispatch(InputKind input) {
      return state.dispatch(Objects.requireNonNull(input, "input"));
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
      onEvent(ClientSemanticEvent.Reset.INSTANCE);
   }

   private void apply(InteractionTransition.Switch change) {
      State previous = this.state;
      previous.exit(this, change.cause());
      this.state = change.target();
      this.state.enter(this, previous, change);
   }

   private void invalidateGesture() {
      generation++;
      gesture = 0L;
      gestureButton = -1;
   }

   public enum State {
      IDLE(Dispatch.VANILLA, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.VANILLA, Dispatch.BLOCKED, false, true),
      BUILDING(Dispatch.BUILDING, Dispatch.BUILDING, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.CANCEL, true, true),
      GEOMETRY(Dispatch.GEOMETRY, Dispatch.GEOMETRY, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.CANCEL, true, true),
      SELECTING(Dispatch.OPERATION, Dispatch.BLOCKED, Dispatch.OPERATION, Dispatch.OPERATION, Dispatch.CANCEL, false, true),
      ADJUSTING(Dispatch.OPERATION, Dispatch.OPERATION, Dispatch.OPERATION, Dispatch.OPERATION, Dispatch.CANCEL, true, true),
      SUBMITTING(Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.CANCEL, false, false),
      PLACING(Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.CANCEL, false, true),
      RESTORING(Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, false, true),
      CANCELLING(Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, Dispatch.BLOCKED, false, false);

      private final Dispatch regular;
      private final Dispatch submit;
      private final Dispatch createSelection;
      private final Dispatch pasteWorkspace;
      private final Dispatch cancel;
      private final boolean canSubmit;
      private final boolean serverObservable;

      State(
         Dispatch regular,
         Dispatch submit,
         Dispatch createSelection,
         Dispatch pasteWorkspace,
         Dispatch cancel,
         boolean canSubmit,
         boolean serverObservable
      ) {
         this.regular = regular;
         this.submit = submit;
         this.createSelection = createSelection;
         this.pasteWorkspace = pasteWorkspace;
         this.cancel = cancel;
         this.canSubmit = canSubmit;
         this.serverObservable = serverObservable;
      }

      InteractionTransition onEvent(
         ClientSemanticEvent event, ClientSemanticEvent.Submit activeRequest, State submissionOrigin
      ) {
         return switch (event) {
            case ClientSemanticEvent.Observe observation ->
               observation.state() == this || !acceptsObservation(observation.state())
                  ? InteractionTransition.Stay.INSTANCE
                  : new InteractionTransition.Switch(observation.state(), InteractionTransition.Cause.OBSERVATION);
            case ClientSemanticEvent.Cancel ignored -> this.cancel == Dispatch.CANCEL
               ? new InteractionTransition.Switch(CANCELLING, InteractionTransition.Cause.CANCEL)
               : new InteractionTransition.Rejected(InteractionTransition.Rejection.INPUT_BLOCKED);
            case ClientSemanticEvent.Reset ignored ->
               new InteractionTransition.Switch(IDLE, InteractionTransition.Cause.RESET);
            case ClientSemanticEvent.Submit request -> submitTransition(request, activeRequest);
            case ClientSemanticEvent.SubmissionCompleted completion ->
               completionTransition(completion, activeRequest, submissionOrigin);
         };
      }

      private InteractionTransition submitTransition(
         ClientSemanticEvent.Submit request, ClientSemanticEvent.Submit activeRequest
      ) {
         if (!this.canSubmit) {
            return new InteractionTransition.Rejected(InteractionTransition.Rejection.INPUT_BLOCKED);
         }
         if (activeRequest != null) {
            return new InteractionTransition.Rejected(InteractionTransition.Rejection.REQUEST_ACTIVE);
         }
         if (!ClientRequestTracker.valid(request)) {
            return new InteractionTransition.Rejected(InteractionTransition.Rejection.INVALID_REQUEST);
         }
         return new InteractionTransition.Switch(SUBMITTING, InteractionTransition.Cause.SUBMIT, request);
      }

      private InteractionTransition completionTransition(
         ClientSemanticEvent.SubmissionCompleted completion,
         ClientSemanticEvent.Submit activeRequest,
         State submissionOrigin
      ) {
         if (this != SUBMITTING || !completion.request().equals(activeRequest)) {
            return new InteractionTransition.Rejected(InteractionTransition.Rejection.STALE_REQUEST);
         }
         State next = Objects.requireNonNull(submissionOrigin, "submissionOrigin")
            .completeSubmission(completion.outcome(), completion.observed());
         return new InteractionTransition.Switch(next, InteractionTransition.Cause.SUBMISSION_COMPLETED);
      }

      private void exit(ClientInputStateMachine owner, InteractionTransition.Cause reason) {
         owner.invalidateGesture();
         owner.requests.clear();
         owner.submissionOrigin = null;
      }

      private void enter(ClientInputStateMachine owner, State previous, InteractionTransition.Switch change) {
         if (this == SUBMITTING) {
            // The decision validates the request before exit releases the previous phase.
            owner.requests.begin(change.submission());
            owner.submissionOrigin = previous;
         }
      }

      Dispatch dispatch(InputKind input) {
         return switch (input) {
            case KEY, POINTER, INTERACTION, SCROLL -> regular;
            case SUBMIT -> submit;
            case CREATE_SELECTION -> createSelection;
            case PASTE_WORKSPACE -> pasteWorkspace;
            case CANCEL -> cancel;
         };
      }

      boolean serverObservable() {
         return serverObservable;
      }

      boolean acceptsObservation(State observed) {
         if (this == SUBMITTING) {
            return false;
         }
         return this != CANCELLING || observed == IDLE;
      }

      State completeSubmission(SubmissionEvent event, State observed) {
         Objects.requireNonNull(event, "event");
         if (event != SubmissionEvent.SUCCEEDED) {
            return this;
         }
         Objects.requireNonNull(observed, "observed");
         if (!observed.serverObservable()) {
            throw new IllegalArgumentException("Successful submission requires an observed server state");
         }
         return observed;
      }
   }

   public enum InputKind {
      KEY,
      SUBMIT,
      POINTER,
      INTERACTION,
      SCROLL,
      CREATE_SELECTION,
      PASTE_WORKSPACE,
      CANCEL
   }

   public enum SubmissionEvent {
      SUCCEEDED,
      FAILED,
      EXPIRED
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
