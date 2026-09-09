package org.example.seedancegenarate.agent.generation;

/** A proven non-acceptance, with a safe user-facing explanation. Unknown outcomes never use this type. */
public class GenerationRejectedException extends RuntimeException {
    public GenerationRejectedException(String message) { super(message); }
}
