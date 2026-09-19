package io.github.fourilla.endervault.web.api.v1.file;

import io.github.fourilla.endervault.auth.ClientIpResolver;
import io.github.fourilla.endervault.common.StorageAccessException;
import io.github.fourilla.endervault.directorytransfer.*;
import io.github.fourilla.endervault.web.support.ActionResponse;
import io.github.fourilla.endervault.web.task.TaskPayload;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/files/directory-merges")
public class DirectoryTransferApiController {
    private final DirectoryTransferPreparationTaskService preparation;
    private final DirectoryTransferTaskService tasks;
    private final DirectoryTransferReviewStore reviews;
    private final DirectoryTransferQueryService query;
    private final ClientIpResolver clientIp;

    public DirectoryTransferApiController(DirectoryTransferPreparationTaskService preparation, DirectoryTransferTaskService tasks,
            DirectoryTransferReviewStore reviews, DirectoryTransferQueryService query, ClientIpResolver clientIp) {
        this.preparation = preparation;
        this.tasks = tasks;
        this.reviews = reviews;
        this.query = query;
        this.clientIp = clientIp;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TaskPayload> prepareTransfer(@RequestParam DirectoryTransferPlan.Operation operation,
            @RequestParam String source, @RequestParam(defaultValue = "") String destination,
            HttpServletRequest request) {
        return ResponseEntity.accepted().body(TaskPayload.from(preparation.transfer(operation, source, destination,
                request.getUserPrincipal().getName(), clientIp.resolve(request))));
    }

    @PostMapping("/pending/{pendingId}")
    public ResponseEntity<TaskPayload> preparePending(@PathVariable String pendingId, HttpServletRequest request) {
        return ResponseEntity.accepted().body(TaskPayload.from(preparation.pending(pendingId,
                request.getUserPrincipal().getName(), clientIp.resolve(request))));
    }

    @GetMapping
    public DirectoryTransferQueryService.Page<DirectoryTransferQueryService.Summary> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) throws IOException {
        return query.list(page, size);
    }

    @GetMapping("/{id}")
    public DirectoryTransferQueryService.Detail get(@PathVariable String id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "false") boolean conflictsOnly) throws IOException {
        return query.get(id, page, size, conflictsOnly);
    }

    @GetMapping("/unresolved")
    public java.util.List<DirectoryTransferQueryService.Summary> unresolved() throws IOException {
        return query.unresolved();
    }

    @PostMapping("/{id}/choices")
    public ChoiceResult choose(@PathVariable String id, @RequestBody Choices request) throws IOException {
        if (request.revision() == null || request.revision() < 0 || request.choices() == null
                || request.choices().isEmpty() || request.choices().size() > 1000
                || request.choices().entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException("A revision and 1-1000 choices are required.");
        }
        var review = reviews.choose(id, request.revision(), request.choices());
        return new ChoiceResult(true, review.revision(), review.fullyReviewed());
    }

    @PostMapping("/{id}/choices/all")
    public ChoiceResult chooseAll(@PathVariable String id, @RequestBody BulkChoice request) throws IOException {
        if (request.revision() == null || request.revision() < 0 || request.choice() == null) {
            throw new IllegalArgumentException("A revision and choice are required.");
        }
        var review = reviews.chooseAll(id, request.revision(), request.choice());
        return new ChoiceResult(true, review.revision(), review.fullyReviewed());
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<TaskPayload> execute(@PathVariable String id, @RequestParam long revision,
            HttpServletRequest request) throws IOException {
        return ResponseEntity.accepted().body(TaskPayload.from(tasks.execute(id, revision,
                request.getUserPrincipal().getName(), clientIp.resolve(request))));
    }

    @PostMapping("/{id}/replan")
    public ResponseEntity<TaskPayload> replan(@PathVariable String id, @RequestParam long revision,
            HttpServletRequest request) throws IOException {
        return ResponseEntity.accepted().body(TaskPayload.from(tasks.replan(id, revision,
                request.getUserPrincipal().getName(), clientIp.resolve(request))));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ActionResponse> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ActionResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(StorageAccessException.class)
    public ResponseEntity<ActionResponse> conflict(StorageAccessException ex) {
        return ResponseEntity.status(409).body(ActionResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(NoSuchFileException.class)
    public ResponseEntity<ActionResponse> missing(NoSuchFileException ex) {
        return ResponseEntity.status(404).body(ActionResponse.error("Directory merge review was not found."));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ActionResponse> unavailable(IOException ex) {
        return ResponseEntity.internalServerError().body(ActionResponse.error("Directory merge data is unavailable. Existing data was retained."));
    }

    public record Choices(Long revision, Map<String, DirectoryTransferReview.Choice> choices) {}
    public record BulkChoice(Long revision, DirectoryTransferReview.Choice choice) {}
    public record ChoiceResult(boolean ok, long revision, boolean fullyReviewed) {}
}
