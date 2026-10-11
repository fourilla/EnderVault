package io.github.fourilla.endervault.config;

import io.github.fourilla.endervault.filesystem.FileSystemOperation;
import io.github.fourilla.endervault.filesystem.FileSystemOperations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** A request keeps one filesystem policy through its mutation and activity recording. */
@Configuration
public class FileSystemWebConfiguration implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OperationInterceptor());
    }

    static final class OperationInterceptor implements AsyncHandlerInterceptor {
        private static final String OPERATION = OperationInterceptor.class.getName() + ".operation";
        private static final String SCOPE = OperationInterceptor.class.getName() + ".scope";

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            FileSystemOperation operation = (FileSystemOperation) request.getAttribute(OPERATION);
            if (operation == null) {
                operation = FileSystemOperations.captureOperation();
                request.setAttribute(OPERATION, operation);
            }
            var previous = (ScopeFrame) request.getAttribute(SCOPE);
            request.setAttribute(SCOPE, new ScopeFrame(operation.activate(), previous));
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                Exception exception) {
            closeScope(request);
            if (request.getAttribute(SCOPE) == null && !request.isAsyncStarted()) {
                request.removeAttribute(OPERATION);
            }
        }

        @Override
        public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                Object handler) {
            // Keep the handle for redispatch, but never leave a policy on the released servlet thread.
            closeScope(request);
        }

        private void closeScope(HttpServletRequest request) {
            ScopeFrame frame = (ScopeFrame) request.getAttribute(SCOPE);
            if (frame != null) {
                if (frame.previous() == null) request.removeAttribute(SCOPE);
                else request.setAttribute(SCOPE, frame.previous());
                frame.scope().close();
            }
        }

        private record ScopeFrame(FileSystemOperation.Scope scope, ScopeFrame previous) { }
    }
}
