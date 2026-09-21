package io.github.fourilla.endervault.directorytransfer;

/** Display wording only; operation, phase and paused remain the execution authority. */
public final class DirectoryTransferPresentation {
    private DirectoryTransferPresentation() {}

    public static String operation(DirectoryTransferPlan.Operation operation) {
        return switch (operation) { case COPY -> "Copy"; case MOVE -> "Move"; case PENDING -> "Upload merge"; };
    }

    public static String title(DirectoryTransferPlan.Operation operation) {
        return switch (operation) { case COPY -> "Directory copy"; case MOVE -> "Directory move"; case PENDING -> "Uploaded directory merge"; };
    }

    public static String status(DirectoryTransferPlan.Operation operation, DirectoryTransferRun run, boolean editable) {
        String name = operation(operation);
        if (run == null) return name + (editable ? " awaiting review" : " ready to resume");
        String phase = switch (run.phase()) {
            case PUBLISHING -> switch (operation) { case COPY -> "Copying"; case MOVE -> "Moving"; case PENDING -> "Merging upload"; };
            case FINALIZING -> name + " finalizing";
            case OWNER_COMPLETING -> name + " completing pending record";
            case NEEDS_REVIEW -> name + " needs review";
            case COMPLETE -> name + " completed";
            case ABANDONED -> name + " abandoned";
            case ABANDONING -> name + " finishing abandonment";
        };
        return run.paused() ? name + " paused (" + switch (run.phase()) {
            case PUBLISHING -> "publication";
            case FINALIZING -> "finalization";
            case OWNER_COMPLETING -> "pending record completion";
            default -> "review";
        } + ")" : phase;
    }
}
