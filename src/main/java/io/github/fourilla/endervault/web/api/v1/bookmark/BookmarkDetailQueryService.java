package io.github.fourilla.endervault.web.api.v1.bookmark;

import io.github.fourilla.endervault.bookmark.BookmarkItem;
import io.github.fourilla.endervault.bookmark.BookmarkService;
import io.github.fourilla.endervault.favorite.FavoriteService;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import org.springframework.stereotype.Service;

@Service
public class BookmarkDetailQueryService {

    private final BookmarkService bookmarkService;
    private final FavoriteService favoriteService;

    public BookmarkDetailQueryService(BookmarkService bookmarkService, FavoriteService favoriteService) {
        this.bookmarkService = bookmarkService;
        this.favoriteService = favoriteService;
    }

    public BookmarkDetailPayload load(String id) throws IOException {
        BookmarkItem item = bookmarkService.find(id);
        if (item == null) {
            throw new NoSuchFileException("Bookmark item not found.");
        }
        BookmarkItem parent = bookmarkService.currentDirectory(item.parentId());
        return BookmarkDetailPayload.from(
                item,
                favoriteService.isBookmarkFavorite(item.id()),
                bookmarkService.metadataFetchEnabled(),
                parent
        );
    }
}
