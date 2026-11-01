package com.keyloop.scheduler.batch.service.outbox.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every sample in docs/contracts/outbox has the fields the relay reads (Spec04 §4.4). */
class OutboxPayloadContractTest {

    private static final Path SAMPLES = Path.of("../docs/contracts/outbox");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static List<Path> samples() throws IOException {
        try (Stream<Path> files = Files.list(SAMPLES)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @ParameterizedTest
    @MethodSource("samples")
    void sampleMatchesTheContract(Path sample) {
        JsonNode payload = JSON.readTree(sample.toFile());

        assertThat(UUID.fromString(payload.required("dealershipId").asString())).isNotNull();
        assertThat(payload.required("dates").isArray()).isTrue();
        for (String field : List.of("dates", "oldDates", "newDates")) {
            payload.path(field).forEach(date -> assertThat(LocalDate.parse(date.asString())).isNotNull());
        }
    }

    @Test
    void oneSamplePerEventType() throws IOException {
        assertThat(samples()).extracting(p -> p.getFileName().toString()).containsExactly(
                "AppointmentBooked.json", "AppointmentCancelled.json", "AppointmentRescheduled.json",
                "BayBlocked.json", "BusinessHoursChanged.json", "TechnicianUnavailable.json");
    }
}
