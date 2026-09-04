package io.github.fourilla.endervault.web.support;

import io.github.fourilla.endervault.common.StorageAccessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@ControllerAdvice
public class WebExceptionHandler {

    private final FrontendAssetModelAdvice frontendAssets;

    public WebExceptionHandler(FrontendAssetModelAdvice frontendAssets) {
        this.frontendAssets = frontendAssets;
    }

    @ExceptionHandler(StorageAccessException.class)
    public Object storageAccess(
            StorageAccessException exception,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        return handleUserFacingException(
                request,
                response,
                redirectAttributes,
                model,
                HttpStatus.FORBIDDEN,
                "Access denied",
                exception.getMessage()
        );
    }

    @ExceptionHandler(NoSuchFileException.class)
    public Object missing(
            NoSuchFileException exception,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        return handleUserFacingException(
                request,
                response,
                redirectAttributes,
                model,
                HttpStatus.NOT_FOUND,
                "Not found",
                exception.getMessage()
        );
    }

    @ExceptionHandler(FileAlreadyExistsException.class)
    public Object alreadyExists(
            FileAlreadyExistsException exception,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model
    ) {
        return handleUserFacingException(
                request,
                response,
                redirectAttributes,
                model,
                HttpStatus.CONFLICT,
                "Already exists",
                "An item with that name already exists."
        );
    }

    private Object handleUserFacingException(
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes,
            Model model,
            HttpStatus status,
            String title,
            String message
    ) {
        String cleanMessage = cleanMessage(message, title);
        if (isApiRequest(request) || JsonRequestSupport.wantsJson(request)) {
            return ResponseEntity.status(status).body(ActionResponse.error(cleanMessage));
        }

        if (shouldRedirectWithToast(request)) {
            FlashNotifications.error(redirectAttributes, cleanMessage);
            return "redirect:" + redirectBackPath(request);
        }

        response.setStatus(status.value());
        model.addAttribute("stylesFrontend", frontendAssets.stylesFrontend());
        model.addAttribute("title", title);
        model.addAttribute("message", cleanMessage);
        return "error";
    }

    private boolean shouldRedirectWithToast(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        return "POST".equalsIgnoreCase(request.getMethod())
                && path.startsWith(contextPath + "/files");
    }

    private boolean isApiRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }

    private String redirectBackPath(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return "/files";
        }

        try {
            URI uri = new URI(referer);
            String contextPath = request.getContextPath();
            String rawPath = uri.getRawPath();
            if (rawPath == null || !rawPath.startsWith(contextPath + "/files")) {
                return "/files";
            }

            String localPath = rawPath.substring(contextPath.length());
            String query = uri.getRawQuery();
            return query == null ? localPath : localPath + "?" + query;
        } catch (URISyntaxException ex) {
            return "/files";
        }
    }

    private String cleanMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
