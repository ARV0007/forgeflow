package com.forgeflow.workspace;

/** What a caller is trying to do to a project. */
public enum Permission {
    /** See it: its files, its chats, its previews. */
    READ,
    /** Change what's in it: generate, chat, build, preview. */
    WRITE,
    /** Change the project itself: rename, delete, publish, manage members. */
    ADMIN
}
