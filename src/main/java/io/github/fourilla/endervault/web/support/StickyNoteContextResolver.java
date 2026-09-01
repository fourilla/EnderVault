package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.stickynote.StickyNoteContext;
import io.github.fourilla.endervault.stickynote.StickyNotePageCatalog;
import io.github.fourilla.endervault.stickynote.StickyNoteSurface;
import io.github.fourilla.endervault.stickynote.StickyNoteTargetType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class StickyNoteContextResolver {

    private final StickyNotePageCatalog pageCatalog;

    public StickyNoteContextResolver(StickyNotePageCatalog pageCatalog) {
        this.pageCatalog = pageCatalog;
    }

    public StickyNotePageContext resolve(HttpServletRequest request) {
        String path = applicationPath(request);
        if (path.equals("/files/read-only") || path.startsWith("/s/") || path.equals("/login")) {
            return StickyNotePageContext.unavailable();
        }

        StickyNoteContext context = storageContext(path, request);
        if (context == null) {
            context = bookmarkContext(path, request);
        }
        if (context == null) {
            StickyNotePageCatalog.PageDefinition page = pageCatalog.byPath(path);
            if (page != null) {
                context = new StickyNoteContext(StickyNoteTargetType.PAGE, page.key(), StickyNoteSurface.PAGE);
            }
        }
        if (context == null) {
            return StickyNotePageContext.unavailable();
        }
        return StickyNotePageContext.from(context, label(context));
    }

    private StickyNoteContext storageContext(String path, HttpServletRequest request) {
        if (path.equals("/files")) {
            return storage(request.getParameter("path"), StickyNoteSurface.BROWSER);
        }
        if (path.equals("/files/detail")) {
            return storage(request.getParameter("path"), StickyNoteSurface.DETAIL);
        }
        return null;
    }

    private StickyNoteContext bookmarkContext(String path, HttpServletRequest request) {
        if (path.equals("/files/bookmarks/detail")) {
            String id = trimmed(request.getParameter("id"));
            return id.isBlank() ? null : bookmark(id, StickyNoteSurface.DETAIL);
        }
        if (path.equals("/files/bookmarks")) {
            String directory = trimmed(request.getParameter("directory"));
            if (!directory.isBlank()) {
                return bookmark(directory, StickyNoteSurface.BROWSER);
            }
        }
        return null;
    }

    private StickyNoteContext storage(String key, StickyNoteSurface surface) {
        return new StickyNoteContext(StickyNoteTargetType.STORAGE, normalizeVaultKey(key), surface);
    }

    private StickyNoteContext bookmark(String key, StickyNoteSurface surface) {
        return new StickyNoteContext(StickyNoteTargetType.BOOKMARK, trimmed(key), surface);
    }

    private String label(StickyNoteContext context) {
        return switch (context.targetType()) {
            case STORAGE -> context.targetKey().isBlank() ? "Files /" : context.targetKey();
            case BOOKMARK -> "Bookmark " + context.targetKey();
            case FILE_REQUEST -> "File Request " + context.targetKey();
            case PAGE -> {
                StickyNotePageCatalog.PageDefinition page = pageCatalog.byKey(context.targetKey());
                yield page == null ? context.targetKey() : page.label();
            }
        };
    }

    private String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private String normalizeVaultKey(String value) {
        String key = trimmed(value).replace('\\', '/');
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        while (key.endsWith("/") && !key.isBlank()) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
