package io.github.fourilla.endervault.web.share;

import io.github.fourilla.endervault.config.NasProperties;
import io.github.fourilla.endervault.filetool.FileToolDescriptor;
import java.nio.file.NoSuchFileException;
import org.springframework.stereotype.Component;

@Component
public class SharedPreviewPolicy {

    private final NasProperties.Share shareProperties;

    public SharedPreviewPolicy(NasProperties nasProperties) {
        this.shareProperties = nasProperties.getShare();
    }

    public boolean isEnabled(FileToolDescriptor fileTool) {
        return shareProperties.isDefaultPreviewEnabled() && fileTool.sharedPreviewable();
    }

    public void requireEnabled(FileToolDescriptor fileTool) throws NoSuchFileException {
        if (!isEnabled(fileTool)) {
            throw new NoSuchFileException("Shared preview is unavailable.");
        }
    }
}
