package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.transfer.TransferBufferService;
import io.github.fourilla.endervault.web.file.browser.FileBrowserQueryService;
import io.github.fourilla.endervault.web.file.browser.FileBrowserResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminFileController {

    private final FileBrowserQueryService queryService;
    private final TransferBufferService transferBufferService;

    public AdminFileController(
            FileBrowserQueryService queryService,
            TransferBufferService transferBufferService
    ) {
        this.queryService = queryService;
        this.transferBufferService = transferBufferService;
    }

    @GetMapping("/files")
    public String files(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "hidden", required = false) String hidden,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) throws IOException {
        FileBrowserResult result = queryService.browse(
                path,
                view,
                sort,
                direction,
                hidden,
                page,
                size,
                request,
                response
        );
        String resolvedView = result.preferences().view();

        model.addAttribute("listing", result.context());
        model.addAttribute("path", result.context().path());
        model.addAttribute("view", resolvedView);
        model.addAttribute("nextView", "grid".equals(resolvedView) ? "table" : "grid");
        model.addAttribute("viewToggleLabel", "grid".equals(resolvedView)
                ? "Switch to table view"
                : "Switch to grid view");
        model.addAttribute("viewToggleIcon", "grid".equals(resolvedView)
                ? "fas fa-bars"
                : "fas fa-border-all");
        addPreferenceModel(model, result);
        model.addAttribute("filePage", result.entries());
        model.addAttribute("favoritePaths", result.favoritePaths());
        model.addAttribute("transferBuffer", transferBufferService.current(request.getSession(false)));
        return "files";
    }

    @GetMapping("/files/search")
    public String search(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "hidden", required = false) String hidden,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) throws IOException {
        FileBrowserResult result = queryService.search(
                path,
                query,
                null,
                null,
                null,
                hidden,
                page,
                size,
                request,
                response
        );

        model.addAttribute("listing", result.context());
        model.addAttribute("path", result.context().path());
        model.addAttribute("query", result.query());
        model.addAttribute("hidden", result.preferences().hiddenMode());
        model.addAttribute("showHidden", result.preferences().showHidden());
        model.addAttribute("searchPerformed", result.searchPerformed());
        model.addAttribute("pageSizes", result.preferences().pageSizeOptions());
        model.addAttribute("resultPage", result.entries());
        return "search";
    }

    @GetMapping("/files/read-only")
    public String readOnly(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "hidden", required = false) String hidden,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) throws IOException {
        FileBrowserResult result = queryService.readOnly(
                path,
                sort,
                direction,
                hidden,
                page,
                size,
                request,
                response
        );

        model.addAttribute("listing", result.context());
        model.addAttribute("path", result.context().path());
        addPreferenceModel(model, result);
        model.addAttribute("filePage", result.entries());
        return "read-only";
    }

    private void addPreferenceModel(Model model, FileBrowserResult result) {
        model.addAttribute("sort", result.preferences().sort().parameter());
        model.addAttribute("dir", result.preferences().direction().parameter());
        model.addAttribute("hidden", result.preferences().hiddenMode());
        model.addAttribute("showHidden", result.preferences().showHidden());
        model.addAttribute("pageSizes", result.preferences().pageSizeOptions());
    }
}
