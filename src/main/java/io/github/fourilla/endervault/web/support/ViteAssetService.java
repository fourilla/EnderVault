package io.github.fourilla.endervault.web.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ViteAssetService {

    private static final Logger log = LoggerFactory.getLogger(ViteAssetService.class);
    private static final String MANIFEST_LOCATION = "classpath:/static/react/.vite/manifest.json";
    private static final String PUBLIC_ROOT = "/react/";

    private final ObjectMapper objectMapper;
    private final Resource manifestResource;
    private final String developmentServer;
    private final AtomicBoolean missingManifestLogged = new AtomicBoolean();

    private volatile Map<String, ManifestChunk> manifest;

    public ViteAssetService(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${endervault.frontend.dev-server-url:}") String developmentServer) {
        this.objectMapper = objectMapper;
        this.manifestResource = resourceLoader.getResource(MANIFEST_LOCATION);
        this.developmentServer = normalizeDevelopmentServer(developmentServer);
    }

    public ViteEntry entry(String source) {
        if (developmentServer != null) {
            return ViteEntry.development(
                    developmentServer + "/@vite/client",
                    developmentServer + "/" + source);
        }

        try {
            Map<String, ManifestChunk> chunks = manifest();
            ManifestChunk entry = findEntry(chunks, source);
            if (entry == null || !StringUtils.hasText(entry.file())) {
                return unavailable("Vite manifest does not contain entry " + source + ".");
            }

            LinkedHashSet<String> styles = new LinkedHashSet<>();
            entry.css().stream().map(this::publicUrl).forEach(styles::add);

            LinkedHashSet<String> modulePreloads = new LinkedHashSet<>();
            collectImports(chunks, entry, new LinkedHashSet<>(), styles, modulePreloads);

            return ViteEntry.production(
                    publicUrl(entry.file()),
                    List.copyOf(styles),
                    List.copyOf(modulePreloads));
        } catch (IOException exception) {
            return unavailable("Unable to read Vite manifest: " + exception.getClass().getSimpleName() + ".");
        }
    }

    private Map<String, ManifestChunk> manifest() throws IOException {
        Map<String, ManifestChunk> current = manifest;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (manifest == null) {
                try (InputStream input = manifestResource.getInputStream()) {
                    manifest = objectMapper.readValue(input, new TypeReference<LinkedHashMap<String, ManifestChunk>>() {
                    });
                }
            }
            return manifest;
        }
    }

    private ManifestChunk findEntry(Map<String, ManifestChunk> chunks, String source) {
        ManifestChunk direct = chunks.get(source);
        if (direct != null) {
            return direct;
        }
        return chunks.values().stream()
                .filter(chunk -> source.equals(chunk.src()) && chunk.entry())
                .findFirst()
                .orElse(null);
    }

    private void collectImports(
            Map<String, ManifestChunk> chunks,
            ManifestChunk chunk,
            Set<String> visited,
            Set<String> styles,
            Set<String> modulePreloads) {
        for (String importKey : chunk.imports()) {
            if (!visited.add(importKey)) {
                continue;
            }
            ManifestChunk imported = chunks.get(importKey);
            if (imported == null) {
                continue;
            }
            collectImports(chunks, imported, visited, styles, modulePreloads);
            imported.css().stream().map(this::publicUrl).forEach(styles::add);
            if (StringUtils.hasText(imported.file())) {
                modulePreloads.add(publicUrl(imported.file()));
            }
        }
    }

    private ViteEntry unavailable(String reason) {
        if (missingManifestLogged.compareAndSet(false, true)) {
            log.warn(reason);
        }
        return ViteEntry.unavailable();
    }

    private String publicUrl(String file) {
        return PUBLIC_ROOT + file;
    }

    private static String normalizeDevelopmentServer(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return null;
        }
        URI uri = URI.create(candidate.trim());
        String host = uri.getHost();
        boolean localHost = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        boolean supportedScheme = "http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme());
        boolean rootPath = !StringUtils.hasText(uri.getPath()) || "/".equals(uri.getPath());
        if (!localHost || !supportedScheme || !rootPath
                || StringUtils.hasText(uri.getQuery()) || StringUtils.hasText(uri.getFragment())) {
            throw new IllegalArgumentException("Vite development server must be a loopback HTTP(S) origin.");
        }
        String normalized = candidate.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    public record ViteEntry(
            boolean available,
            boolean development,
            String clientScript,
            String entryScript,
            List<String> styles,
            List<String> modulePreloads) {

        static ViteEntry unavailable() {
            return new ViteEntry(false, false, null, null, List.of(), List.of());
        }

        static ViteEntry development(String clientScript, String entryScript) {
            return new ViteEntry(true, true, clientScript, entryScript, List.of(), List.of());
        }

        static ViteEntry production(String entryScript, List<String> styles, List<String> modulePreloads) {
            return new ViteEntry(true, false, null, entryScript, styles, modulePreloads);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ManifestChunk(
            String file,
            String src,
            @JsonProperty("isEntry") Boolean entry,
            List<String> css,
            List<String> imports) {

        private ManifestChunk {
            entry = Boolean.TRUE.equals(entry);
            css = css == null ? List.of() : List.copyOf(css);
            imports = imports == null ? List.of() : List.copyOf(imports);
        }
    }
}
