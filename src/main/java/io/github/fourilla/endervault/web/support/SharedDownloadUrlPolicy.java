package io.github.fourilla.endervault.web.support;

/** Chooses the existing named or query-only download endpoint without relaxing the firewall. */
final class SharedDownloadUrlPolicy {

    private SharedDownloadUrlPolicy() {
    }

    static boolean canIncludeFilenameInPath(String name) {
        // Spring Security rejects encoded percent and semicolon in URL paths.
        return name.indexOf('%') < 0 && name.indexOf(';') < 0;
    }
}
