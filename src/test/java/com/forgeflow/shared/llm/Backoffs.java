package com.forgeflow.shared.llm;

/** Lets a fake model behave like GeminiClient obeying a 429: sleep, then record the wait. */
public final class Backoffs {

    private Backoffs() {
    }

    public static void simulate(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        BackoffClock.waited(millis);
    }
}
