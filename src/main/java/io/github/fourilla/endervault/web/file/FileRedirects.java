package io.github.fourilla.endervault.web.file;

import io.github.fourilla.endervault.web.support.InternalUrlBuilder;

public final class FileRedirects {

    private FileRedirects() {
    }

    static String redirectToFiles(String path) {
        return "redirect:" + filesUrl(path);
    }

    static String redirectToFiles(String path, String view) {
        return redirectToFiles(path, view, null, null, null, null);
    }

    static String redirectToFiles(
            String path,
            String view,
            String sort,
            String direction,
            Integer page,
            Integer size
    ) {
        return "redirect:" + filesUrl(path, view, sort, direction, page, size);
    }

    static String redirectToDetail(String path) {
        return "redirect:" + detailUrl(path);
    }

    public static String filesUrl(String path) {
        return filesUrl(path, null, null, null, null, null);
    }

    public static String filesUrl(
            String path,
            String view,
            String sort,
            String direction,
            Integer page,
            Integer size
    ) {
        int pageNumber = page == null ? 1 : Math.max(1, page);

        InternalUrlBuilder builder = InternalUrlBuilder.fromPath("/files");
        if (path != null && !path.isBlank()) {
            builder.queryParam("path", path);
        }
        if (pageNumber > 1) {
            builder.queryParam("page", pageNumber);
        }
        return builder.build();
    }

    public static String detailUrl(String path) {
        return InternalUrlBuilder.fromPath("/files/detail").queryParam("path", path).build();
    }

    public static String targetPath(String directoryPath, String filename) {
        if (directoryPath == null || directoryPath.isBlank()) {
            return filename;
        }
        return directoryPath + "/" + filename;
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
