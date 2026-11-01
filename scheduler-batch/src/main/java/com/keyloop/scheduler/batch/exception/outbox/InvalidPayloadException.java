package com.keyloop.scheduler.batch.exception.outbox;

/** Retrying cannot fix it: the event goes to FAILED on the first try (Spec04 §4.3). */
public class InvalidPayloadException extends RuntimeException {

    public InvalidPayloadException(String reason) {
        super(reason);
    }
}
