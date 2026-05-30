package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageUsage;
import io.github.fourilla.endervault.web.dashboard.AdminDashboardController;
import io.github.fourilla.endervault.web.dashboard.AdminLogController;
import io.github.fourilla.endervault.web.file.AdminFileController;
import io.github.fourilla.endervault.web.share.AdminShareController;
import io.github.fourilla.endervault.web.trash.AdminTrashController;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {
        AdminDashboardController.class,
        AdminFileController.class,
        AdminLogController.class,
        AdminShareController.class,
        AdminTrashController.class
})
public class AdminShellModelAdvice {

    private final StorageService storageService;

    public AdminShellModelAdvice(StorageService storageService) {
        this.storageService = storageService;
    }

    @ModelAttribute("storageUsage")
    public StorageUsage storageUsage() {
        return storageService.storageUsage();
    }
}
