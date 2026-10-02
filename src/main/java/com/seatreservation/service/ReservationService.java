package com.seatreservation.service;

import com.seatreservation.dto.ReservationResponse;
import com.seatreservation.dto.ReserveSeatsRequest;
import com.seatreservation.entity.IdempotencyRecord;
import com.seatreservation.entity.Reservation;
import com.seatreservation.entity.ReservationSeat;
import com.seatreservation.entity.ReservationStatus;
import com.seatreservation.entity.Seat;
import com.seatreservation.entity.SeatStatus;
import com.seatreservation.entity.Show;
import com.seatreservation.entity.UserShowBooking;
import com.seatreservation.exception.BookingLimitExceededException;
import com.seatreservation.exception.IdempotencyConflictException;
import com.seatreservation.exception.InvalidReservationRequestException;
import com.seatreservation.exception.ReservationAccessDeniedException;
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
    public ReservationResponse reserveSeats(
            Long showId,
            String userId,
            String idempotencyKey,
            ReserveSeatsRequest request) {

        // 1. Check whether the show exists
        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new ShowNotFoundException("Show not found"));

        // 2. Reject duplicate seats like ["A1", "A1"]
        Set<String> uniqueSeatCodes =
                new HashSet<>(request.getSeats());

        if (uniqueSeatCodes.size() != request.getSeats().size()) {
            throw new InvalidReservationRequestException(
                    "Duplicate seat codes are not allowed");
        }

        // 3. Create deterministic fingerprint of this request
        String requestHash =
                RequestHashUtil.hashReservationRequest(
                        showId,
                        request.getSeats());

        // 4. Atomically create idempotency record if this is a new key
        idempotencyRecordRepository.createIfNotExists(
                userId,
                idempotencyKey,
                requestHash);

        // 5. Lock idempotency record
        IdempotencyRecord idempotencyRecord =
                idempotencyRecordRepository
                        .findForUpdate(userId, idempotencyKey)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Idempotency record not found"));

        // 6. Same key but different request
        if (!idempotencyRecord
                .getRequestHash()
                .equals(requestHash)) {

            throw new IdempotencyConflictException(
                    "Idempotency key was already used with a different request");
        }

        // 7. Same key + same request already completed
        // Return original reservation
        if (idempotencyRecord.getReservation() != null) {
            return toReservationResponse(
                    idempotencyRecord.getReservation());
        }

        // 8. Ensure user/show counter exists
        userShowBookingRepository.createIfNotExists(
                userId,
                showId);

        // Lock user's counter for this show
        UserShowBooking userBooking =
                userShowBookingRepository
                        .findForUpdate(userId, showId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "User booking record not found"));

        int requestedSeats = request.getSeats().size();

        // 9. Enforce maximum 4 confirmed seats per user/show
        if (userBooking.getConfirmedSeats()
                + requestedSeats > 4) {

            throw new BookingLimitExceededException(
                    "User cannot reserve more than 4 seats");
        }

        // 10. Lock requested seat rows.
        // Repository orders them deterministically by seat code.
        List<Seat> seats =
                seatRepository.findSeatsForUpdate(
                        showId,
                        request.getSeats());

        // 11. Make sure all requested seats exist
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

            // Record which reservation currently owns this seat
            seat.setCurrentReservation(savedReservation);

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

        // 16. Store successful result against idempotency key
        idempotencyRecord.setReservation(savedReservation);

        return toReservationResponse(savedReservation);
    }

    @Transactional
    public ReservationResponse cancelReservation(
            Long reservationId,
            String userId) {

        // 1. Lock reservation so two cancellation requests
        // cannot process it simultaneously
        Reservation reservation =
                reservationRepository
                        .findForUpdate(reservationId)
                        .orElseThrow(() ->
                                new InvalidReservationRequestException(
                                        "Reservation not found"));

        // 2. Only owner can cancel
        if (!reservation.getUserId().equals(userId)) {
            throw new ReservationAccessDeniedException(
                    "Reservation does not belong to this user");
        }

        // 3. Cancellation is idempotent
        if (reservation.getStatus()
                == ReservationStatus.CANCELLED) {

            return toReservationResponse(reservation);
        }

        // 4. Find seats belonging to this reservation
        List<ReservationSeat> reservationSeats =
                reservationSeatRepository
                        .findByReservationId(reservationId);

        List<String> seatCodes =
                reservationSeats.stream()
                        .map(rs ->
                                rs.getSeat().getSeatCode())
                        .sorted()
                        .toList();

        /*
         * IMPORTANT LOCK ORDER:
         *
         * Reserve:
         * user/show counter -> seats
         *
         * Cancel:
         * user/show counter -> seats
         *
         * Keeping the same order reduces deadlock risk.
         */

        // 5. Lock user's per-show booking counter FIRST
        UserShowBooking userBooking =
                userShowBookingRepository
                        .findForUpdate(
                                userId,
                                reservation.getShow().getId())
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "User booking record not found"));

        // 6. Then lock seat rows in deterministic order
        List<Seat> lockedSeats =
                seatRepository.findSeatsForUpdate(
                        reservation.getShow().getId(),
                        seatCodes);

        // 7. Release only seats STILL owned by this reservation
        int releasedSeats = 0;

        for (Seat seat : lockedSeats) {

            if (seat.getCurrentReservation() != null
                    && seat.getCurrentReservation()
                            .getId()
                            .equals(reservationId)) {

                seat.setStatus(SeatStatus.AVAILABLE);
                seat.setCurrentReservation(null);
                releasedSeats++;
            }
        }

        // 8. Decrease confirmed-seat counter
        userBooking.setConfirmedSeats(
                userBooking.getConfirmedSeats()
                        - releasedSeats);

        // 9. Mark reservation cancelled
        reservation.setStatus(
                ReservationStatus.CANCELLED);

        return toReservationResponse(reservation);
    }

    private ReservationResponse toReservationResponse(
            Reservation reservation) {

        List<ReservationSeat> reservationSeats =
                reservationSeatRepository
                        .findByReservationId(
                                reservation.getId());

        List<String> seatCodes =
                reservationSeats.stream()
                        .map(rs ->
                                rs.getSeat().getSeatCode())
                        .sorted()
                        .toList();

        long amountPaise =
                reservation.getShow().getPricePaise()
                        * seatCodes.size();

        return new ReservationResponse(
                reservation.getId(),
                reservation.getShow().getId(),
                reservation.getUserId(),
                seatCodes,
                amountPaise,
                reservation.getStatus()
                        .name()
                        .toLowerCase());
    }
}