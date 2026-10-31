package dev.finflow.importing.api;

import dev.finflow.auth.api.AuthenticatedUser;
import dev.finflow.importing.parser.BankFormat;
import dev.finflow.importing.service.ImportService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportResponse upload(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam BankFormat bankFormat, @RequestParam("file") MultipartFile file) {
        return importService.upload(principal.userId(), bankFormat, file);
    }

    @GetMapping("/{id}")
    public ImportResponse get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID id) {
        return importService.get(principal.userId(), id);
    }
}
