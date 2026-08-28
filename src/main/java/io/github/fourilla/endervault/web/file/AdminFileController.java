package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.web.file.browser.FileBrowserQueryService;
import io.github.fourilla.endervault.web.file.browser.FileBrowserResult;
import io.github.fourilla.endervault.web.support.ViteAssetService;
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
    private final ViteAssetService viteAssetService;

    public AdminFileController(
            FileBrowserQueryService queryService,
            ViteAssetService viteAssetService
    ) {
        this.queryService = queryService;
        this.viteAssetService = viteAssetService;
    }

    @GetMapping("/files")
    public String files(
            Model model
    ) {
        model.addAttribute("filesFrontend", viteAssetService.entry("src/files/main.tsx"));
        return "files";
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
