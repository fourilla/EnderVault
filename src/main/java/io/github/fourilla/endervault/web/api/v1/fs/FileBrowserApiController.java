package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.web.file.browser.FileBrowserQueryService;
import io.github.fourilla.endervault.web.file.browser.FileBrowserResult;
import io.github.fourilla.endervault.web.support.FilePreviewSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fs")
public class FileBrowserApiController {

    private final FileBrowserQueryService queryService;
    private final FilePreviewSupport filePreviewSupport;

    public FileBrowserApiController(
            FileBrowserQueryService queryService,
            FilePreviewSupport filePreviewSupport
    ) {
        this.queryService = queryService;
        this.filePreviewSupport = filePreviewSupport;
    }

    @GetMapping(value = "/listing", produces = MediaType.APPLICATION_JSON_VALUE)
    public FileBrowserPayload listing(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "hidden", required = false) String hidden,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            HttpServletResponse response
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
        return FileBrowserPayload.from(result, filePreviewSupport);
    }

    @GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
    public FileBrowserPayload search(
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "view", required = false) String view,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "dir", required = false) String direction,
            @RequestParam(value = "hidden", required = false) String hidden,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        FileBrowserResult result = queryService.search(
                path,
                query,
                view,
                sort,
                direction,
                hidden,
                page,
                size,
                request,
                response
        );
        return FileBrowserPayload.from(result, filePreviewSupport);
    }
}
