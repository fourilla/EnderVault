package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.storage.StorageService;
import io.github.fourilla.endervault.storage.StorageUsage;
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
