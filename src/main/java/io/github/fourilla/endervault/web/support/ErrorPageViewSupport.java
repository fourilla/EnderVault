package io.github.fourilla.endervault.web.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ErrorPageViewSupport implements ErrorViewResolver {

    private final FrontendAssetModelAdvice frontendAssets;
    private final AppearanceModelAdvice appearance;

    public ErrorPageViewSupport(FrontendAssetModelAdvice frontendAssets, AppearanceModelAdvice appearance) {
        this.frontendAssets = frontendAssets;
        this.appearance = appearance;
    }

    public void populate(Map<String, Object> model, String title, String message) {
        // Exception and container error models do not inherit normal controller model attributes.
        model.put("stylesFrontend", frontendAssets.stylesFrontend());
        model.put("appearanceStylesheet", appearance.appearanceStylesheet());
        model.put("title", title);
        model.put("message", message);
    }

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        ModelAndView view = new ModelAndView("error");
        // Keep container exception messages, stack traces and private paths out of the HTML view.
        populate(view.getModel(), status.getReasonPhrase(), switch (status) {
            case NOT_FOUND -> "The requested page was not found.";
            case FORBIDDEN -> "You do not have access to this page.";
            default -> "Something went wrong.";
        });
        view.setStatus(status);
        return view;
    }
}
