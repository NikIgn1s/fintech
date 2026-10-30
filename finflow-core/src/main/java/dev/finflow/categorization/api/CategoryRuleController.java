package dev.finflow.categorization.api;

import dev.finflow.auth.security.AuthenticatedUser;
import dev.finflow.categorization.service.CategorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/category-rules")
@RequiredArgsConstructor
public class CategoryRuleController {
    private final CategorizationService service;
    public record Request(@NotBlank @Size(max=255) String pattern, @Positive short categoryId, int priority) {}

    @GetMapping
    public List<CategorizationService.Rule> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.rules(user.userId());
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public CategorizationService.Rule create(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody Request request) {
        return service.createRule(user.userId(), request.pattern(), request.categoryId(), request.priority());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        service.deleteRule(user.userId(), id);
    }
}
