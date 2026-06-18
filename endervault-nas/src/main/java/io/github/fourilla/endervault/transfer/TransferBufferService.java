package io.github.fourilla.endervault.transfer;

import io.github.fourilla.endervault.storage.FileItem;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TransferBufferService {

    private static final String SESSION_ATTRIBUTE =
            TransferBufferService.class.getName() + ".TRANSFER_BUFFER";

    public TransferBuffer current(HttpSession session) {
        if (session == null) {
            return TransferBuffer.empty();
        }
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        return value instanceof TransferBuffer buffer ? buffer : TransferBuffer.empty();
    }

    public TransferBuffer add(HttpSession session, List<FileItem> items) {
        TransferBuffer current = current(session);
        List<TransferBufferItem> candidates = new ArrayList<>();
        candidates.addAll(current.items());
        items.stream()
                .map(item -> new TransferBufferItem(item.path(), item.name(), item.directory()))
                .forEach(candidates::add);

        TransferBuffer next = new TransferBuffer(collapse(candidates));
        session.setAttribute(SESSION_ATTRIBUTE, next);
        return next;
    }

    public TransferBuffer remove(HttpSession session, String itemPath) {
        TransferBuffer current = current(session);
        String normalizedPath = normalize(itemPath);
        if (normalizedPath.isBlank()) {
            return current;
        }

        List<TransferBufferItem> remaining = current.items().stream()
                .filter(item -> !normalize(item.path()).equals(normalizedPath))
                .toList();
        if (remaining.isEmpty()) {
            clear(session);
            return TransferBuffer.empty();
        }

        TransferBuffer next = new TransferBuffer(remaining);
        session.setAttribute(SESSION_ATTRIBUTE, next);
        return next;
    }

    public TransferBuffer replace(HttpSession session, List<TransferBufferItem> items) {
        if (items == null || items.isEmpty()) {
            clear(session);
            return TransferBuffer.empty();
        }

        TransferBuffer next = new TransferBuffer(items);
        if (session != null) {
            session.setAttribute(SESSION_ATTRIBUTE, next);
        }
        return next;
    }

    public void clear(HttpSession session) {
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }

    private List<TransferBufferItem> collapse(List<TransferBufferItem> candidates) {
        LinkedHashMap<String, TransferBufferItem> collapsed = new LinkedHashMap<>();
        for (TransferBufferItem candidate : candidates) {
            if (candidate.path() == null || candidate.path().isBlank()) {
                continue;
            }
            if (isCoveredByExistingDirectory(collapsed, candidate.path()) || collapsed.containsKey(candidate.path())) {
                continue;
            }
            if (candidate.directory()) {
                collapsed.entrySet().removeIf(entry -> isDescendant(candidate.path(), entry.getKey()));
            }
            collapsed.put(candidate.path(), candidate);
        }
        return List.copyOf(collapsed.values());
    }

    private boolean isCoveredByExistingDirectory(LinkedHashMap<String, TransferBufferItem> selected, String path) {
        return selected.values().stream()
                .anyMatch(item -> item.directory() && isDescendant(item.path(), path));
    }

    private boolean isDescendant(String parentPath, String childPath) {
        String parent = normalize(parentPath);
        String child = normalize(childPath);
        return !parent.isBlank() && child.startsWith(parent + "/");
    }

    private String normalize(String path) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
