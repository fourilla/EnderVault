package io.github.fourilla.endervault.web.support;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

public final class BrowserPreferenceCookies {

    public static final Scope FILES = new Scope(
            "endervault.files.view",
            "endervault.files.sort",
            "endervault.files.dir",
            "endervault.files.size",
            "endervault.files.hidden"
    );

    public static final Scope RECENT = new Scope(
            FILES.viewCookie(),
            "endervault.recent.sort",
            "endervault.recent.dir",
            FILES.pageSizeCookie(),
            FILES.hiddenCookie()
    );

    public static final Scope READ_ONLY = new Scope(
            null,
            "endervault.readonly.sort",
            "endervault.readonly.dir",
            "endervault.readonly.size",
            "endervault.readonly.hidden"
    );

    private static final Duration MAX_AGE = Duration.ofDays(365);

    private BrowserPreferenceCookies() {
    }

    public static String value(
            HttpServletRequest request,
            HttpServletResponse response,
            String cookieName,
            String requestedValue,
            Function<String, String> normalizer
    ) {
        if (requestedValue != null) {
            String normalized = normalizer.apply(requestedValue);
            remember(request, response, cookieName, normalized);
            return normalized;
        }

        return cookieValue(request, cookieName)
                .map(normalizer)
                .orElseGet(() -> normalizer.apply(null));
    }

    public static String value(
            HttpServletRequest request,
            String cookieName,
            Function<String, String> normalizer
    ) {
        return cookieValue(request, cookieName)
                .map(normalizer)
                .orElseGet(() -> normalizer.apply(null));
    }

    public static int intValue(
            HttpServletRequest request,
            HttpServletResponse response,
            String cookieName,
            Integer requestedValue,
            Function<Integer, Integer> normalizer
    ) {
        if (requestedValue != null) {
            int normalized = normalizer.apply(requestedValue);
            remember(request, response, cookieName, String.valueOf(normalized));
            return normalized;
        }

        return cookieValue(request, cookieName)
                .flatMap(BrowserPreferenceCookies::parseInt)
                .map(normalizer)
                .orElseGet(() -> normalizer.apply(null));
    }

    public static void clear(HttpServletResponse response, Scope scope) {
        clear(
                response,
                scope.viewCookie(),
                scope.sortCookie(),
                scope.directionCookie(),
                scope.pageSizeCookie(),
                scope.hiddenCookie()
        );
    }

    private static Optional<String> cookieValue(HttpServletRequest request, String cookieName) {
        if (cookieName == null || request.getCookies() == null) {
            return Optional.empty();
        }

        return Arrays.stream(request.getCookies())
                .filter(cookie -> cookieName.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private static Optional<Integer> parseInt(String value) {
        try {
            return Optional.of(Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static void remember(
            HttpServletRequest request,
            HttpServletResponse response,
            String cookieName,
            String value
    ) {
        if (cookieName == null || value == null || value.isBlank()) {
            return;
        }

        ResponseCookie cookie = ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .sameSite("Lax")
                .secure(request.isSecure())
                .path("/")
                .maxAge(MAX_AGE)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static void clear(HttpServletResponse response, String... cookieNames) {
        for (String cookieName : cookieNames) {
            if (cookieName == null) {
                continue;
            }

            ResponseCookie cookie = ResponseCookie.from(cookieName, "")
                    .httpOnly(true)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(Duration.ZERO)
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
    }

    public record Scope(
            String viewCookie,
            String sortCookie,
            String directionCookie,
            String pageSizeCookie,
            String hiddenCookie
    ) {
    }
}
