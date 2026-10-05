package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SelectedItemActions {
    public static final int MAX_ITEMS = 200;
    private static final Logger logger = LoggerFactory.getLogger(SelectedItemActions.class);

    private SelectedItemActions() { }

    public static List<String> validate(List<String> ids, String action, String expectedAction,
            boolean confirmed, Predicate<String> validId) {
        if (!expectedAction.equals(action) || !confirmed) {
            throw new StorageAccessException("Select a supported action and confirm the selected items.");
        }
        if (ids == null || ids.isEmpty() || ids.size() > MAX_ITEMS) {
            throw new StorageAccessException("Select between 1 and " + MAX_ITEMS + " items.");
        }
        var unique = new HashSet<String>();
        for (String id : ids) {
            if (id == null || !validId.test(id) || !unique.add(id)) {
                throw new StorageAccessException("Selected item keys are invalid or duplicated.");
            }
        }
        return List.copyOf(ids);
    }

    public static boolean canonicalUuid(String id) {
        try {
            return UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static Response execute(List<String> ids, String label, Action action) {
        var results = new ArrayList<ItemResult>();
        for (String id : ids) {
            try {
                results.add(new ItemResult(id, Status.APPLIED, action.apply(id)));
            } catch (NoSuchFileException ex) {
                results.add(new ItemResult(id, Status.NOT_FOUND, "This item is no longer available. Refresh the list."));
            } catch (StorageAccessException ex) {
                results.add(new ItemResult(id, Status.REJECTED, "This action was rejected. Refresh the list before retrying."));
            } catch (IOException | RuntimeException ex) {
                logger.warn("Selected {} action failed ({}).", label, ex.getClass().getSimpleName());
                results.add(new ItemResult(id, Status.FAILED, "Could not complete this action. Refresh the list before retrying."));
            }
        }
        int succeeded = (int) results.stream().filter(item -> item.status() == Status.APPLIED).count();
        int failed = results.size() - succeeded;
        String message = label + " processed: " + succeeded + " succeeded, " + failed + " unsuccessful.";
        var notification = failed == 0 ? FlashNotification.success(message)
                : succeeded == 0 ? FlashNotification.error(message) : FlashNotification.warning(message);
        return new Response(true, notification, succeeded, failed, List.copyOf(results));
    }

    @FunctionalInterface
    public interface Action { String apply(String id) throws IOException; }
    public enum Status { APPLIED, NOT_FOUND, REJECTED, FAILED }
    public record ItemResult(String id, Status status, String message) { }
    public record Response(boolean ok, FlashNotification notification, int succeededCount, int failedCount,
            List<ItemResult> results) { }
}
