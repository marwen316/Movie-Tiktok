package com.codenova.moviefactory.controller;

import com.codenova.moviefactory.service.ProductionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionControllerTest {
    private final ProductionService service=mock(ProductionService.class);
    private final ProductionController controller=new ProductionController(service);

    @Test
    void createReturnsPersistedId() {
        UUID id=UUID.randomUUID();
        when(service.create("Film",2024,"Synopsis")).thenReturn(id);
        ResponseEntity<?> result=controller.create(new ProductionController.CreateMovie("Film",2024,"Synopsis"));
        assertEquals(HttpStatus.CREATED,result.getStatusCode());
        assertEquals(id,((Map<?,?>)result.getBody()).get("id"));
        verify(service).create("Film",2024,"Synopsis");
    }
    @Test
    void generationReturnsAccepted() {
        UUID movie=UUID.randomUUID(), episode=UUID.randomUUID();
        when(service.start(movie)).thenReturn(episode);
        ResponseEntity<?> result=controller.generate(movie);
        assertEquals(HttpStatus.ACCEPTED,result.getStatusCode());
        assertEquals(episode,((Map<?,?>)result.getBody()).get("episodeId"));
    }
    @Test
    void approvalDelegatesToService() {
        UUID episode=UUID.randomUUID();
        assertEquals("APPROVED",((Map<?,?>)controller.approve(episode)).get("status"));
        verify(service).approve(episode);
    }
    @Test
    void unknownEpisodeReturns404() {
        UUID episode=UUID.randomUUID();
        when(service.episode(episode)).thenThrow(new NoSuchElementException("Episode not found: "+episode));
        try {
            controller.episode(episode);
            fail("Expected missing episode");
        } catch (NoSuchElementException e) {
            assertEquals(HttpStatus.NOT_FOUND,controller.notFound(e).getStatusCode());
        }
    }
    @Test
    void missingVideoReturns404() {
        UUID episode=UUID.randomUUID();
        when(service.episode(episode)).thenReturn(Map.of("status","QUEUED"));
        assertEquals(HttpStatus.NOT_FOUND,controller.video(episode).getStatusCode());
    }
}
