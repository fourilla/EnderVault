package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.StorageService;
import java.io.IOException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminTrashController {

    private final StorageService storageService;

    public AdminTrashController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/files/trash")
    public String trash(Model model) throws IOException {
        DirectoryListing listing = storageService.listTrash();
        model.addAttribute("listing", listing);
        return "trash";
    }
}
