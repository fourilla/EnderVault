package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.storage.DirectoryListing;
import io.github.fourilla.endervault.storage.StorageScope;
import io.github.fourilla.endervault.storage.StorageService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

@Controller
public class AdminFileController {

    private final StorageService storageService;
    private final FileResponseService fileResponseService;

    public AdminFileController(StorageService storageService, FileResponseService fileResponseService) {
        this.storageService = storageService;
        this.fileResponseService = fileResponseService;
    }

    @GetMapping("/files")
    public String files(@RequestParam(value = "path", required = false) String path, Model model) throws IOException {
        DirectoryListing listing = storageService.list(StorageScope.VAULT, path);
        model.addAttribute("listing", listing);
        model.addAttribute("path", listing.path());
        model.addAttribute("guestMode", false);
        return "files";
    }

    @PostMapping("/files/upload")
    public String upload(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("files") MultipartFile[] files,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        for (MultipartFile file : files) {
            storageService.upload(path, file);
        }
        redirectAttributes.addFlashAttribute("message", "Upload complete.");
        return redirectToFiles(path);
    }

    @PostMapping("/files/folders")
    public String createFolder(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("name") String name,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        storageService.createDirectory(path, name);
        redirectAttributes.addFlashAttribute("message", "Folder created.");
        return redirectToFiles(path);
    }

    @PostMapping("/files/rename")
    public String rename(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam("newName") String newName,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        storageService.rename(path, item, newName);
        redirectAttributes.addFlashAttribute("message", "Item renamed.");
        return redirectToFiles(path);
    }

    @PostMapping("/files/move")
    public String move(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestParam("targetPath") String targetPath,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        storageService.move(path, item, targetPath);
        redirectAttributes.addFlashAttribute("message", "Item moved.");
        return redirectToFiles(path);
    }

    @PostMapping("/files/delete")
    public String delete(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "items", required = false) List<String> items,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        if (items == null || items.isEmpty()) {
            redirectAttributes.addFlashAttribute("message", "Select at least one item.");
            return redirectToFiles(path);
        }
        storageService.delete(path, items);
        redirectAttributes.addFlashAttribute("message", "Selected items deleted.");
        return redirectToFiles(path);
    }

    @GetMapping("/files/download")
    public ResponseEntity<?> download(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item
    ) throws IOException {
        Path file = storageService.resolveFile(StorageScope.VAULT, path, item);
        return fileResponseService.attachment(file);
    }

    @GetMapping("/files/download.zip")
    public void downloadZip(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "items", required = false) List<String> items,
            HttpServletResponse response
    ) throws IOException {
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"endervault.zip\"");
        if (items != null && !items.isEmpty()) {
            storageService.writeZip(StorageScope.VAULT, path, items, response.getOutputStream());
        }
    }

    @GetMapping("/files/preview")
    public ResponseEntity<?> preview(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam("item") String item,
            @RequestHeader HttpHeaders headers
    ) throws IOException {
        Path file = storageService.resolveFile(StorageScope.VAULT, path, item);
        return fileResponseService.inline(file, headers);
    }

    private String redirectToFiles(String path) {
        if (path == null || path.isBlank()) {
            return "redirect:/files";
        }
        return "redirect:/files?path=" + UriUtils.encodeQueryParam(path, StandardCharsets.UTF_8);
    }
}
