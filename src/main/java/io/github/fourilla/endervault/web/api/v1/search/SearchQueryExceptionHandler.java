package io.github.fourilla.endervault.web.api.v1.search;

import io.github.fourilla.endervault.search.SearchQueryException;
import io.github.fourilla.endervault.web.support.FlashNotification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "io.github.fourilla.endervault.web.api.v1")
public class SearchQueryExceptionHandler {

    @ExceptionHandler(SearchQueryException.class)
    public ResponseEntity<SearchQueryErrorResponse> invalidQuery(SearchQueryException exception) {
        return ResponseEntity.badRequest().body(new SearchQueryErrorResponse(
                false, FlashNotification.error(exception.getMessage()), exception.position()));
    }
}
