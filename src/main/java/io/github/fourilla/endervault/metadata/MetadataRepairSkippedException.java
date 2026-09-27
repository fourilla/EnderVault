package io.github.fourilla.endervault.metadata;

/** The finding is stale or no longer eligible; no repair was performed. */
public class MetadataRepairSkippedException extends RuntimeException {
    public MetadataRepairSkippedException(String message) { super(message); }
}
