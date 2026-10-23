package com.bookleaf.support.web;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * GET /api/v1/health (TDD section 6): public readiness check. It confirms the database answers
 * but reports only UP or DOWN: no secrets, versions or database details.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        try {
            em.createNativeQuery("select 1").getSingleResult();
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
        }
    }
}
