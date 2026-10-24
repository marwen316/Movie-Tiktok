package com.codenova.moviefactory.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/movies")
public class MovieController {
    public record CreateMovieRequest(@NotBlank String title, Integer releaseYear, @NotBlank String sourceSynopsis) {}
    public record MovieResponse(UUID id, String title, String status) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MovieResponse create(@Valid @RequestBody CreateMovieRequest request) {
        return new MovieResponse(UUID.randomUUID(), request.title(), "DRAFT");
    }
}
