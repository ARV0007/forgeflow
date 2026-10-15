package com.forgeflow.shared;

/**
 * The request can't be done as asked, and the message says why in words a
 * person can act on ("That repo's zip is larger than 20 MB"). Answered as a
 * 400 problem with that message as its detail.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
