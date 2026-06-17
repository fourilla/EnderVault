package io.github.fourilla.endervault.web.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

public final class ActionResponseSupport {

    private ActionResponseSupport() {
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

    public static Object ok(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            FlashNotification notification,
            String redirectViewName
    ) {
        return ok(request, redirectAttributes, notification, redirectViewName, ActionResponse.ok(notification));
    }

    public static Object ok(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            FlashNotification notification,
            String redirectViewName,
            Object jsonBody
    ) {
        if (wantsJson(request)) {
            return ResponseEntity.ok(jsonBody);
        }
        FlashNotifications.add(redirectAttributes, notification);
        return redirectViewName;
    }

    public static Object redirect(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            FlashNotification notification,
            String redirectViewName
    ) {
        if (wantsJson(request)) {
            return ResponseEntity.ok(ActionResponse.redirect(notification, redirectUrl(redirectViewName)));
        }
        FlashNotifications.add(redirectAttributes, notification);
        return redirectViewName;
    }

    public static Object badRequest(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            FlashNotification notification,
            String redirectViewName
    ) {
        return error(HttpStatus.BAD_REQUEST, request, redirectAttributes, notification, redirectViewName);
    }

    public static Object error(
            HttpStatus status,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            FlashNotification notification,
            String redirectViewName
    ) {
        if (wantsJson(request)) {
            return ResponseEntity.status(status).body(ActionResponse.error(notification.message()));
        }
        FlashNotifications.add(redirectAttributes, notification);
        return redirectViewName;
    }

    public static String redirectUrl(String redirectViewName) {
        return redirectViewName.startsWith("redirect:")
                ? redirectViewName.substring("redirect:".length())
                : redirectViewName;
    }
}
