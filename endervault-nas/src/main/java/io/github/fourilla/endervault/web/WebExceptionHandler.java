package io.github.fourilla.endervault.web;

import io.github.fourilla.endervault.common.StorageAccessException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

@ControllerAdvice
public class WebExceptionHandler {

    @ExceptionHandler(StorageAccessException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String storageAccess(StorageAccessException exception, Model model) {
        model.addAttribute("title", "Access denied");
        model.addAttribute("message", exception.getMessage());
        return "error";
    }

    @ExceptionHandler(NoSuchFileException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String missing(NoSuchFileException exception, Model model) {
        model.addAttribute("title", "Not found");
        model.addAttribute("message", exception.getMessage());
        return "error";
    }

    @ExceptionHandler(FileAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String alreadyExists(FileAlreadyExistsException exception, Model model) {
        model.addAttribute("title", "Already exists");
        model.addAttribute("message", exception.getMessage());
        return "error";
    }
}

