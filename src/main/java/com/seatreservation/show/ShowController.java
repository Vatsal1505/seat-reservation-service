package com.seatreservation.show;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ShowResponse> create(@Valid @RequestBody CreateShowRequest request) {
        ShowResponse show = showService.create(request);
        return ResponseEntity.created(URI.create("/shows/" + show.id())).body(show);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ShowResponse get(@PathVariable UUID id) {
        return showService.get(id);
    }
}
