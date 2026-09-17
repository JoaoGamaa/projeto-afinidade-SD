package afinidade;

import afinidade.Protocol.*;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("!servidor")
@RequestMapping("/api")
public class WebController {

    private final TcpClient gateway;

    public WebController(TcpClient gateway) {
        this.gateway = gateway;
    }

    public record RatingUpdate(Long version, List<Integer> ratings) {}

    @GetMapping("/catalog")
    public ResponseEntity<?> catalog() {
        return relay(new Request("CATALOG", null, null, null, null, null));
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return relay(new Request("PING", null, null, null, null, null));
    }

    @GetMapping("/users/{id}/recommendations")
    public ResponseEntity<?> recommendations(
        @PathVariable long id,
        @RequestParam(defaultValue = "3") int k,
        @RequestParam(defaultValue = "5") int limit
    ) {
        return relay(new Request("RECOMMEND", id, null, null, k, limit));
    }

    @PutMapping("/users/{id}/ratings")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody RatingUpdate body) {
        return relay(new Request("UPDATE_RATINGS", id, body.ratings(), body.version(), null, null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> unavailable(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(
            Map.of("message", exception.getReason())
        );
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class })
    public ResponseEntity<?> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(
            Map.of("message", "Dados inválidos. Use números inteiros e JSON válido.")
        );
    }

    private ResponseEntity<?> relay(Request request) {
        Response response = gateway.exchange(request);
        if (response.ok()) return ResponseEntity.ok(response.data());
        int status = switch (response.code()) {
            case "INVALID_REQUEST" -> 400;
            case "NOT_FOUND" -> 404;
            case "CONFLICT" -> 409;
            default -> 502;
        };
        return ResponseEntity.status(status).body(response);
    }
}
