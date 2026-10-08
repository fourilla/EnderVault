package io.github.fourilla.endervault.web.support;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "io.github.fourilla.endervault.web")
public class FrontendAssetModelAdvice {

    private static final String STYLES_ENTRY = "src/styles/main.ts";
    private static final String SHELL_ENTRY = "src/shell/main.ts";
    private static final String FILE_TOOLS_ENTRY = "src/file-tools/main.ts";
    private static final String PUBLIC_SHARE_ENTRY = "src/public-share/main.tsx";
    private static final String MARKDOWN_ENTRY = "src/markdown/main.ts";

    private final ViteAssetService.ViteEntry stylesEntry;
    private final ViteAssetService.ViteEntry shellEntry;
    private final ViteAssetService.ViteEntry fileToolsEntry;
    private final ViteAssetService.ViteEntry publicShareEntry;
    private final ViteAssetService.ViteEntry markdownEntry;
    private final ViteAssetService.ViteEntry dialogsEntry;
    private final ViteAssetService.ViteEntry requestUploadEntry;

    public FrontendAssetModelAdvice(ViteAssetService viteAssetService) {
        this.stylesEntry = viteAssetService.entry(STYLES_ENTRY);
        this.shellEntry = viteAssetService.entry(SHELL_ENTRY);
        this.fileToolsEntry = viteAssetService.entry(FILE_TOOLS_ENTRY);
        this.publicShareEntry = viteAssetService.entry(PUBLIC_SHARE_ENTRY);
        this.markdownEntry = viteAssetService.entry(MARKDOWN_ENTRY);
        this.dialogsEntry = viteAssetService.entry("src/dialogs/main.tsx");
        this.requestUploadEntry = viteAssetService.entry("src/public-request/upload.js");
    }

    @ModelAttribute("requestUploadFrontend")
    public ViteAssetService.ViteEntry requestUploadFrontend() { return requestUploadEntry; }

    @ModelAttribute("stylesFrontend")
    public ViteAssetService.ViteEntry stylesFrontend() {
        return stylesEntry;
    }

    @ModelAttribute("dialogsFrontend")
    public ViteAssetService.ViteEntry dialogsFrontend() {
        return dialogsEntry;
    }

    @ModelAttribute("shellFrontend")
    public ViteAssetService.ViteEntry shellFrontend() {
        return shellEntry;
    }

    @ModelAttribute("fileToolsFrontend")
    public ViteAssetService.ViteEntry fileToolsFrontend() {
        return fileToolsEntry;
    }

    @ModelAttribute("markdownFrontend")
    public ViteAssetService.ViteEntry markdownFrontend() {
        return markdownEntry;
    }

    @ModelAttribute("publicShareFrontend")
    public ViteAssetService.ViteEntry publicShareFrontend() {
        return publicShareEntry;
    }
}
