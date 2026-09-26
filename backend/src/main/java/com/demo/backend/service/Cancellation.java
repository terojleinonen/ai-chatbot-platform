package com.demo.backend.service;

/**
 * Lets another thread stop a call in progress: {@link #cancel()} runs the action the call registered for its
 * current step (aborting the request, or closing the response stream), and later steps see {@link #isCancelled()}.
 */
public class Cancellation {
    private Runnable action;
    private boolean cancelled;

    /** Sets what cancelling does from now on; runs it immediately if already cancelled. */
    public void onCancel(Runnable newAction) {
        synchronized (this) {
            if (!cancelled) {
                action = newAction;
                return;
            }
        }
        newAction.run();
    }

    public void cancel() {
        Runnable toRun;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            toRun = action;
        }
        if (toRun != null) toRun.run();
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }
}
