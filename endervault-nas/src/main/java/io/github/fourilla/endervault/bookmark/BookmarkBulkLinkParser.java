package io.github.fourilla.endervault.bookmark;

import io.github.fourilla.endervault.bookmark.BookmarkService.BulkLinkInput;
import io.github.fourilla.endervault.common.StorageAccessException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class BookmarkBulkLinkParser {

    private static final int MAX_BULK_LINKS = 1000;
    private static final int MAX_BULK_TEXT_LENGTH = 5_000_000;

    List<BulkLinkInput> parse(
            String bulkText,
            Predicate<String> validUrlLine,
            Consumer<BulkLinkInput> validateInput
    ) {
        validateTextLength(bulkText);
        List<BulkLinkInput> inputs = parseLines(bulkText, validUrlLine);
        if (inputs.isEmpty()) {
            throw new StorageAccessException("Bulk add text is required.");
        }
        if (inputs.size() > MAX_BULK_LINKS) {
            throw new StorageAccessException("Bulk add is limited to " + MAX_BULK_LINKS + " links at a time.");
        }
        inputs.forEach(validateInput);
        return List.copyOf(inputs);
    }

    private void validateTextLength(String bulkText) {
        if (bulkText != null && bulkText.length() > MAX_BULK_TEXT_LENGTH) {
            throw new StorageAccessException("Bulk add text is too large.");
        }
    }

    private List<BulkLinkInput> parseLines(String bulkText, Predicate<String> validUrlLine) {
        List<String> lines = normalizedLines(bulkText);
        List<BulkLinkInput> inputs = new ArrayList<>();
        String pendingTitle = null;

        for (String line : lines) {
            if (validUrlLine.test(line)) {
                inputs.add(new BulkLinkInput(pendingTitle == null ? "" : pendingTitle, line));
                pendingTitle = null;
                continue;
            }

            if (pendingTitle != null) {
                throw new StorageAccessException("Bulk add has consecutive titles without a URL.");
            }
            pendingTitle = line;
        }

        if (pendingTitle != null) {
            throw new StorageAccessException("Bulk add title must be followed by a URL.");
        }
        return inputs;
    }

    private List<String> normalizedLines(String bulkText) {
        if (bulkText == null || bulkText.isBlank()) {
            return List.of();
        }
        return bulkText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }
}
