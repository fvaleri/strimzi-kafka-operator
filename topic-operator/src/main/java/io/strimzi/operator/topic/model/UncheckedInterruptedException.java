/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Used to wrap {@code InterruptedException} within methods that do not declare it in their {@code throws} clause.
 */
public class UncheckedInterruptedException extends RuntimeException {
    /**
     * @param cause The cause.
     */
    public UncheckedInterruptedException(InterruptedException cause) {
        super(cause);
    }

    @SuppressFBWarnings("BC_UNCONFIRMED_CAST_OF_RETURN_VALUE")
    @Override
    public synchronized InterruptedException getCause() {
        return (InterruptedException) super.getCause();
    }
}
