package com.seatreservation.metrics;

import com.seatreservation.entity.SeatStatus;
import com.seatreservation.repository.SeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter confirmedCounter;
    private final Counter idempotentReplayCounter;

    public ReservationMetrics(
            MeterRegistry meterRegistry,
            SeatRepository seatRepository) {

        this.meterRegistry = meterRegistry;

        this.confirmedCounter = Counter.builder(
                        "reservations.confirmed")
                .description("Number of successfully confirmed reservations")
                .register(meterRegistry);

        this.idempotentReplayCounter = Counter.builder(
                        "reservations.idempotent.replays")
                .description("Number of successful idempotent reservation replays")
                .register(meterRegistry);

        Gauge.builder(
                        "seats.available",
                        seatRepository,
                        repository ->
                                repository.countByStatus(
                                        SeatStatus.AVAILABLE))
                .description("Number of currently available seats")
                .register(meterRegistry);
    }

    public void reservationConfirmed() {
        confirmedCounter.increment();
    }

    public void idempotentReplay() {
        idempotentReplayCounter.increment();
    }

    public void reservationDeclined(String reason) {

        Counter.builder("reservations.declined")
                .description("Number of declined reservation requests")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }
}