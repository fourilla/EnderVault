package io.github.fourilla.endervault.web.share;

import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import io.github.fourilla.endervault.share.ShareLink;
import java.nio.file.NoSuchFileException;
import org.springframework.stereotype.Component;

@Component
public class SharedPreviewPolicy {

    public boolean isEnabled(ShareLink shareLink, FileToolDescriptor fileTool) {
        return shareLink.previewEnabled() && fileTool.sharedPreviewable();
    }

    public void requireEnabled(ShareLink shareLink, FileToolDescriptor fileTool) throws NoSuchFileException {
        if (!isEnabled(shareLink, fileTool)) {
            throw new NoSuchFileException("Shared preview is unavailable.");
        }
    }
}
