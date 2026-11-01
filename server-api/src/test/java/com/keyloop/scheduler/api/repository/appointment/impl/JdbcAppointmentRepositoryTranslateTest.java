package com.keyloop.scheduler.api.repository.appointment.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.api.exception.appointment.DuplicateIdempotencyKeyException;
import com.keyloop.scheduler.api.exception.appointment.ResourceGoneException;
import com.keyloop.scheduler.api.exception.appointment.SlotTakenException;
import com.keyloop.scheduler.api.exception.appointment.SlotTakenException.Resource;
import com.keyloop.scheduler.api.exception.appointment.VehicleOwnerMismatchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/** Spec07 §7.2 / BK-09: every SQLSTATE + constraint name mapping, without thread timing. */
class JdbcAppointmentRepositoryTranslateTest {

    /** A Spring error wrapping a PostgreSQL error with this SQLSTATE and constraint (null = none). */
    private static DataAccessException error(String sqlState, String constraint) {
        StringBuilder fields = new StringBuilder("SERROR\0C").append(sqlState).append("\0Mtest\0");
        if (constraint != null) {
            fields.append('n').append(constraint).append('\0');
        }
        PSQLException psql = new PSQLException(new ServerErrorMessage(fields.toString()));
        return new DataIntegrityViolationException("insert failed", psql);
    }

    @ParameterizedTest
    @CsvSource({
            "23P01, ex_appointments_bay,        BAY",
            "23P01, ex_appointments_technician, TECHNICIAN",
            "23P01, ex_appointments_vehicle,    VEHICLE",
    })
    void exclusionViolationIsSlotTaken(String state, String constraint, Resource resource) {
        assertThat(JdbcAppointmentRepository.translate(error(state, constraint)))
                .isInstanceOfSatisfying(SlotTakenException.class, e -> assertThat(e.resource()).isEqualTo(resource));
    }

    @Test
    void deadlockIsLostPair() {
        assertThat(JdbcAppointmentRepository.translate(error("40P01", null)))
                .isInstanceOfSatisfying(SlotTakenException.class,
                        e -> assertThat(e.resource()).isEqualTo(Resource.PAIR));
    }

    @ParameterizedTest
    @CsvSource({"fk_appointments_bay_type", "fk_appointments_bay_dealer", "fk_appointments_tech_type",
            "fk_appointments_tech_skill", "fk_appointments_tech_dealer"})
    void bayOrTechnicianForeignKeyIsResourceGone(String constraint) {
        assertThat(JdbcAppointmentRepository.translate(error("23503", constraint)))
                .isInstanceOf(ResourceGoneException.class);
    }

    @Test
    void vehicleOwnerForeignKeyIsOwnerMismatch() {
        assertThat(JdbcAppointmentRepository.translate(error("23503", "fk_appointments_vehicle_owner")))
                .isInstanceOf(VehicleOwnerMismatchException.class);
    }

    @Test
    void idempotencyKeyUniqueIsDuplicateKey() {
        assertThat(JdbcAppointmentRepository.translate(error("23505", "appointments_idempotency_key_key")))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "23P01, ex_other",
            "23503, fk_appointments_vehicle_type",
            "23505, appointments_pkey",
            "23514, ck_appointments_window",
            "23503, ",
    })
    void unmappedStaysTheOriginalDataAccessException(String state, String constraint) {
        DataAccessException original = error(state, constraint);
        assertThat(JdbcAppointmentRepository.translate(original)).isSameAs(original);
    }

    @Test
    void withoutPostgresCauseStaysTheOriginal() {
        DataAccessException original = new DataIntegrityViolationException("no cause");
        assertThat(JdbcAppointmentRepository.translate(original)).isSameAs(original);
    }
}
