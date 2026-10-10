package com.forgeflow.shared.events;

/** Topic names, in one place: a typo here is a consumer that silently never fires. */
public final class Topics {

    private Topics() {
    }

    /** A run changed files. Key: project id, so one project's events stay in order. */
    public static final String CODE_GENERATED = "code.generated";

    /** Where a message goes after its handler has failed every retry. */
    public static String deadLetter(String topic) {
        return topic + ".DLT";
    }
}
