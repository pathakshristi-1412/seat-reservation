package com.seatreservation.service;

import com.seatreservation.dto.ReserveSeatsRequest;
import com.seatreservation.entity.IdempotencyRecord;
import com.seatreservation.entity.Reservation;
import com.seatreservation.entity.ReservationSeat;
import com.seatreservation.entity.Seat;
import com.seatreservation.entity.SeatStatus;
import com.seatreservation.entity.Show;
import com.seatreservation.entity.UserShowBooking;
import com.seatreservation.exception.BookingLimitExceededException;
import com.seatreservation.exception.IdempotencyConflictException;
import com.seatreservation.exception.InvalidReservationRequestException;
import com.seatreservation.exception.SeatUnavailableException;
import com.seatreservation.exception.ShowNotFoundException;
import com.seatreservation.repository.IdempotencyRecordRepository;
import com.seatreservation.repository.ReservationRepository;
import com.seatreservation.repository.ReservationSeatRepository;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import com.seatreservation.repository.UserShowBookingRepository;
import com.seatreservation.util.RequestHashUtil;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final UserShowBookingRepository userShowBookingRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public ReservationService(
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            ReservationSeatRepository reservationSeatRepository,
            UserShowBookingRepository userShowBookingRepository,
            IdempotencyRecordRepository idempotencyRecordRepository) {

        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.userShowBookingRepository = userShowBookingRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    @Transactional
    public Reservation reserveSeats(
            Long showId,
            String userId,
            String idempotencyKey,
            ReserveSeatsRequest request) {

        // 1. Check whether the show exists
        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new ShowNotFoundException("Show not found"));

        // 2. Reject duplicate seat numbers like [3, 3]
        Set<Integer> uniqueSeatNumbers =
                new HashSet<>(request.getSeats());

        if (uniqueSeatNumbers.size() != request.getSeats().size()) {
            throw new InvalidReservationRequestException(
                    "Duplicate seat numbers are not allowed");
        }

        // 3. Create deterministic fingerprint of this request
        String requestHash =
                RequestHashUtil.hashReservationRequest(
                        showId,
                        request.getSeats());

        // 4. Atomically create the idempotency record if this is a new key
        idempotencyRecordRepository.createIfNotExists(
                userId,
                idempotencyKey,
                requestHash);

        // 5. Lock the idempotency record
        IdempotencyRecord idempotencyRecord =
                idempotencyRecordRepository
                        .findForUpdate(userId, idempotencyKey)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Idempotency record not found"));

        // 6. Same key but different request -> conflict
        if (!idempotencyRecord.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency key was already used with a different request");
        }

        // 7. Same key + same request already completed
        // Return the ORIGINAL reservation instead of booking again
        if (idempotencyRecord.getReservation() != null) {
            return idempotencyRecord.getReservation();
        }

        // 8. From here onwards this is a NEW reservation operation

        // Ensure one user/show counter row exists
        userShowBookingRepository.createIfNotExists(
                userId,
                showId);

        // Lock that user's counter for this show
        UserShowBooking userBooking =
                userShowBookingRepository
                        .findForUpdate(userId, showId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "User booking record not found"));

        int requestedSeats = request.getSeats().size();

        // 9. Enforce maximum 4 confirmed seats per user/show
        if (userBooking.getConfirmedSeats() + requestedSeats > 4) {
            throw new BookingLimitExceededException(
                    "User cannot reserve more than 4 seats");
        }

        // 10. Lock requested seat rows
        List<Seat> seats =
                seatRepository.findSeatsForUpdate(
                        showId,
                        request.getSeats());

        // 11. Make sure all requested seats actually exist
        if (seats.size() != request.getSeats().size()) {
            throw new InvalidReservationRequestException(
                    "One or more requested seats do not exist");
        }

        // 12. All-or-nothing availability check
        for (Seat seat : seats) {

            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new SeatUnavailableException(
                        "One or more requested seats are already reserved");
            }
        }

        // 13. Create reservation
        Reservation reservation =
                new Reservation(show, userId);

        Reservation savedReservation =
                reservationRepository.save(reservation);

        // 14. Connect reservation to requested seats
        List<ReservationSeat> reservationSeats =
                new ArrayList<>();

        for (Seat seat : seats) {

            seat.setStatus(SeatStatus.CONFIRMED);

            reservationSeats.add(
                    new ReservationSeat(
                            savedReservation,
                            seat));
        }

        reservationSeatRepository.saveAll(reservationSeats);

        // 15. Update user's confirmed-seat counter
        userBooking.setConfirmedSeats(
                userBooking.getConfirmedSeats()
                        + seats.size());

        // 16. Store the successful result against the idempotency key
        idempotencyRecord.setReservation(savedReservation);

        return savedReservation;
    }
}