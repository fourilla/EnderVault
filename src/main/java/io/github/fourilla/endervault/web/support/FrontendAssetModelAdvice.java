package io.github.fourilla.endervault.web.support;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "io.github.fourilla.endervault.web")
public class FrontendAssetModelAdvice {

    private static final String STYLES_ENTRY = "src/styles/main.ts";
    private static final String SHELL_ENTRY = "src/shell/main.ts";
    private static final String FILE_TOOLS_ENTRY = "src/file-tools/main.ts";
    private static final String SHARED_IMAGE_ENTRY = "src/shared-file/image.tsx";
    private static final String SHARED_COMIC_ENTRY = "src/shared-file/comic.tsx";
    private static final String MARKDOWN_ENTRY = "src/markdown/main.ts";

    private final ViteAssetService.ViteEntry stylesEntry;
    private final ViteAssetService.ViteEntry shellEntry;
    private final ViteAssetService.ViteEntry fileToolsEntry;
    private final ViteAssetService.ViteEntry sharedImageEntry;
    private final ViteAssetService.ViteEntry sharedComicEntry;
    private final ViteAssetService.ViteEntry markdownEntry;

    public FrontendAssetModelAdvice(ViteAssetService viteAssetService) {
        this.stylesEntry = viteAssetService.entry(STYLES_ENTRY);
        this.shellEntry = viteAssetService.entry(SHELL_ENTRY);
        this.fileToolsEntry = viteAssetService.entry(FILE_TOOLS_ENTRY);
        this.sharedImageEntry = viteAssetService.entry(SHARED_IMAGE_ENTRY);
        this.sharedComicEntry = viteAssetService.entry(SHARED_COMIC_ENTRY);
        this.markdownEntry = viteAssetService.entry(MARKDOWN_ENTRY);
    }

    @ModelAttribute("stylesFrontend")
    public ViteAssetService.ViteEntry stylesFrontend() {
        return stylesEntry;
    }

    @ModelAttribute("shellFrontend")
    public ViteAssetService.ViteEntry shellFrontend() {
        return shellEntry;
    }

    @ModelAttribute("fileToolsFrontend")
    public ViteAssetService.ViteEntry fileToolsFrontend() {
        return fileToolsEntry;
    }

    @ModelAttribute("sharedImageFrontend")
    public ViteAssetService.ViteEntry sharedImageFrontend() {
        return sharedImageEntry;
    }

    @ModelAttribute("markdownFrontend")
    public ViteAssetService.ViteEntry markdownFrontend() {
        return markdownEntry;
    }

    @ModelAttribute("sharedComicFrontend")
    public ViteAssetService.ViteEntry sharedComicFrontend() {
        return sharedComicEntry;
    }
}
