package com.bookleaf.support.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

/**
 * Runs the sample data import at startup, but only when app.seed.enabled=true.
 * Usage (from the backend folder):
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--app.seed.enabled=true --spring.main.web-application-type=none"
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class SampleDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);

    private final SampleDataImporter importer;
    private final ObjectMapper objectMapper;
    private final Resource dataset;

    public SampleDataSeeder(
            SampleDataImporter importer,
            ObjectMapper objectMapper,
            @Value("${app.seed.resource}") Resource dataset) {
        this.importer = importer;
        this.objectMapper = objectMapper;
        this.dataset = dataset;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        JsonNode root;
        try (InputStream in = dataset.getInputStream()) {
            root = objectMapper.readTree(in);
        }
        SampleDataImporter.ImportSummary summary = importer.importDataset(root);
        log.info("Sample data imported: {} authors, {} books (plus the admin account).",
                summary.authors(), summary.books());
    }
}
