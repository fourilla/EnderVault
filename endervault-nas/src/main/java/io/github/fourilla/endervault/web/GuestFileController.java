package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class GuestFileController {

    private final StorageService storageService;
    private final FileResponseService fileResponseService;

    public GuestFileController(StorageService storageService, FileResponseService fileResponseService) {
        this.storageService = storageService;
        this.fileResponseService = fileResponseService;
    }

    @GetMapping("/guest")
    public String guest(@RequestParam(value = "path", required = false) String path, Model model) throws IOException {
        DirectoryListing listing = storageService.list(StorageScope.PUBLIC, path);
        model.addAttribute("listing", listing);
        model.addAttribute("path", listing.path());
        model.addAttribute("guestMode", true);
        return "guest";
    }

    @GetMapping("/guest/download")
    public ResponseEntity<?> download(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item
    ) throws IOException {
        Path file = storageService.resolveFile(StorageScope.PUBLIC, path, item);
        return fileResponseService.attachment(file);
    }

    @GetMapping("/guest/download.zip")
    public void downloadZip(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "items", required = false) List<String> items,
            HttpServletResponse response
    ) throws IOException {
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"public-files.zip\"");
        if (items != null && !items.isEmpty()) {
            storageService.writeZip(StorageScope.PUBLIC, path, items, response.getOutputStream());
        }
    }

    @GetMapping("/guest/preview")
    public ResponseEntity<?> preview(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestHeader HttpHeaders headers
    ) throws IOException {
        Path file = storageService.resolveFile(StorageScope.PUBLIC, path, item);
        return fileResponseService.inline(file, headers);
    }
}

