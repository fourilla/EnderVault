package io.github.fourilla.endervault.metadata;

import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.favorite.FavoriteItem;
import io.github.fourilla.endervault.favorite.FavoriteService;
import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.task.TaskContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FavoriteMetadataInspector implements MetadataInspector {

    private final FavoriteService favoriteService;
    private final StorageService storageService;

    public FavoriteMetadataInspector(FavoriteService favoriteService, StorageService storageService) {
        this.favoriteService = favoriteService;
        this.storageService = storageService;
    }

    @Override
    public MetadataArea area() {
        return MetadataArea.FAVORITES;
    }

    @Override
    public List<MetadataIssue> inspect() throws IOException {
        return inspect(null);
    }

    @Override
    public List<MetadataIssue> inspect(TaskContext context) throws IOException {
        List<MetadataIssue> issues = new ArrayList<>();
        for (FavoriteItem favorite : favoriteService.list()) {
            if (context != null) {
                context.checkCanceled();
            }
            try {
                storageService.describeVaultPath(favorite.path());
            } catch (IOException | StorageAccessException ex) {
                issues.add(new MetadataIssue(
                        area(),
                        MetadataIssueSeverity.WARNING,
                        MetadataIssueAction.REMOVE_METADATA,
                        favorite.path(),
                        "Favorite target is missing",
                        favorite.path(),
                        "Remove this stale favorite entry."
                ));
            }
        }
        return List.copyOf(issues);
    }

    @Override
    public String repair(MetadataIssueAction action, String subject) throws IOException {
        if (action != MetadataIssueAction.REMOVE_METADATA) {
            throw new IllegalArgumentException("Unsupported favorite repair action.");
        }
        favoriteService.remove(subject);
        return "Removed stale favorite metadata: " + subject;
    }
}
