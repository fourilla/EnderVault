package io.github.fourilla.endervault.web.api.v1.fs;

import io.github.fourilla.endervault.web.file.detail.FileDetailQueryService;
import io.github.fourilla.endervault.web.file.detail.FileDetailResult;
import io.github.fourilla.endervault.web.support.FileActionViewSupport;
import io.github.fourilla.endervault.web.support.FilePreviewSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fs")
public class FileDetailApiController {

    private final FileDetailQueryService queryService;
    private final FilePreviewSupport filePreviewSupport;
    private final FileActionViewSupport fileActionViewSupport;

    public FileDetailApiController(
            FileDetailQueryService queryService,
            FilePreviewSupport filePreviewSupport,
            FileActionViewSupport fileActionViewSupport
    ) {
        this.queryService = queryService;
        this.filePreviewSupport = filePreviewSupport;
        this.fileActionViewSupport = fileActionViewSupport;
    }

    @GetMapping(value = "/detail", produces = MediaType.APPLICATION_JSON_VALUE)
    public FileDetailPayload detail(
            @RequestParam("path") String path,
            @RequestParam(value = "comicPage", required = false) Integer comicPage,
            HttpServletRequest request
    ) throws IOException {
        FileDetailResult result = queryService.query(path, comicPage, request);
        return FileDetailPayload.from(result, filePreviewSupport, fileActionViewSupport);
    }
}
