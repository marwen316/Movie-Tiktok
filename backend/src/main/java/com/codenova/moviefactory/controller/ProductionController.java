package com.codenova.moviefactory.controller;

import com.codenova.moviefactory.service.ProductionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ProductionController {
    private final ProductionService service;
    public ProductionController(ProductionService service) {this.service=service;}
    public record CreateMovie(@NotBlank String title,Integer releaseYear,@NotBlank String sourceSynopsis) {}
    public record VerifyAsset(@NotBlank String evidence) {}

    @PostMapping("/movies")
    public ResponseEntity<?> create(@Valid @RequestBody CreateMovie request) {
        UUID id=service.create(request.title(),request.releaseYear(),request.sourceSynopsis());
        return ResponseEntity.status(201).body(Map.of("id",id,"status","DRAFT"));
    }
    @GetMapping("/movies/{id}")
    public Object movie(@PathVariable UUID id) {return service.movie(id);}
    @GetMapping("/movies/{id}/episodes")
    public Object episodes(@PathVariable UUID id) {return service.episodes(id);}
    @PostMapping("/movies/{id}/generate")
    public ResponseEntity<?> generate(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(Map.of("episodeId",service.start(id),"status","QUEUED"));
    }
    @GetMapping("/episodes/{id}")
    public Object episode(@PathVariable UUID id) {return service.episode(id);}
    @GetMapping("/episodes/{id}/assets")
    public Object assets(@PathVariable UUID id) {return service.assets(id);}
    @PostMapping("/assets/{id}/verify")
    public Object verify(@PathVariable UUID id,@Valid @RequestBody VerifyAsset request) {
        service.verifyAsset(id,request.evidence()); return Map.of("status","VERIFIED");
    }
    @PostMapping("/episodes/{id}/approve")
    public Object approve(@PathVariable UUID id) {
        service.approve(id); return Map.of("status","APPROVED");
    }
    @GetMapping("/episodes/{id}/video")
    public ResponseEntity<FileSystemResource> video(@PathVariable UUID id) {
        Map<String,Object> episode=service.episode(id);
        if(!List.of("READY_FOR_REVIEW","APPROVED").contains(episode.get("status"))) return ResponseEntity.notFound().build();
        Path file=Path.of((String)episode.get("video_path"));
        if(!Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=episode-"+id+".mp4")
            .body(new FileSystemResource(file));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error",String.valueOf(e.getMessage())));
    }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
    public ResponseEntity<?> badRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error",String.valueOf(e.getMessage())));
    }
}
