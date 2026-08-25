package io.github.fourilla.endervault.web.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

public final class JsonRequestSupport {

    private JsonRequestSupport() {
    }

    public static boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return true;
        }

        String requestedWith = request.getHeader("X-Requested-With");
        return "fetch".equalsIgnoreCase(requestedWith)
                || "XMLHttpRequest".equalsIgnoreCase(requestedWith);
    }
}
