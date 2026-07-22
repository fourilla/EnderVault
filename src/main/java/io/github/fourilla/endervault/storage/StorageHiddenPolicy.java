package io.github.fourilla.endervault.storage;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Locale;

final class StorageHiddenPolicy {

    private StorageHiddenPolicy() {
    }

    static boolean isHidden(Path path) throws IOException {
        return Files.isHidden(path);
    }

    static boolean containsHiddenElement(Path base, Path target) throws IOException {
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedBase)) {
            throw new StorageAccessException("Path is outside the allowed storage area.");
        }
        if (normalizedTarget.equals(normalizedBase)) {
            return false;
        }

        Path current = normalizedBase;
        for (Path segment : normalizedBase.relativize(normalizedTarget)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && isHidden(current)) {
                return true;
            }
        }
        return false;
    }

    static boolean setDosHiddenIfSupported(Path path, boolean hidden) throws IOException {
        if (!isWindows()) {
            return false;
        }

        DosFileAttributeView view = Files.getFileAttributeView(
                path,
                DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            return false;
        }
        view.setHidden(hidden);
        return isHidden(path) == hidden;
    }

    static String hiddenName(String name) {
        return name.startsWith(".") ? name : "." + name;
    }

    static String visibleName(String name) {
        String visible = name.replaceFirst("^\\.+", "");
        if (visible.isBlank()) {
            throw new StorageAccessException("Cannot unhide item with only dot characters.");
        }
        return visible;
    }

    static boolean isWindowsOs(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isWindows() {
        return isWindowsOs(System.getProperty("os.name"));
    }
}
