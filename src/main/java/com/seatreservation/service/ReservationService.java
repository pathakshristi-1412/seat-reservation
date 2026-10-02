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
import com.seatreservation.metrics.ReservationMetrics;
import com.seatreservation.repository.IdempotencyRecordRepository;
import com.seatreservation.repository.ReservationRepository;
import com.seatreservation.repository.ReservationSeatRepository;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import com.seatreservation.repository.UserShowBookingRepository;
import com.seatreservation.util.RequestHashUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ReservationService {

    private static final Logger log =
            LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final UserShowBookingRepository userShowBookingRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ReservationMetrics reservationMetrics;

    public ReservationService(
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            ReservationSeatRepository reservationSeatRepository,
            UserShowBookingRepository userShowBookingRepository,
            IdempotencyRecordRepository idempotencyRecordRepository,
            ReservationMetrics reservationMetrics) {

        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.userShowBookingRepository = userShowBookingRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.reservationMetrics = reservationMetrics;
    }

    @Transactional
    public ReservationResponse reserveSeats(
            Long showId,
            String userId,
            String idempotencyKey,
            ReserveSeatsRequest request) {

        log.info(
                "reservation_request show_id={} user_id={} seats={}",
                showId,
                userId,
                request.getSeats());

        // 1. Check whether show exists
        Show show = showRepository.findById(showId)
                .orElseThrow(() ->
                        new ShowNotFoundException("Show not found"));

        // 2. Reject duplicate seats like ["A1", "A1"]
        Set<String> uniqueSeatCodes =
                new HashSet<>(request.getSeats());

        if (uniqueSeatCodes.size() != request.getSeats().size()) {

            log.warn(
                    "reservation_declined reason=duplicate_seats show_id={} user_id={} seats={}",
                    showId,
                    userId,
                    request.getSeats());

            throw new InvalidReservationRequestException(
                    "Duplicate seat codes are not allowed");
        }

        // 3. Create deterministic fingerprint for idempotency
        String requestHash =
                RequestHashUtil.hashReservationRequest(
                        showId,
                        request.getSeats());

        // 4. Create idempotency record if this is a new key
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

        // 6. Same key + different request = conflict
        if (!idempotencyRecord
                .getRequestHash()
                .equals(requestHash)) {

            log.warn(
                    "reservation_declined reason=idempotency_conflict show_id={} user_id={}",
                    showId,
                    userId);

            throw new IdempotencyConflictException(
                    "Idempotency key was already used with a different request");
        }

        // 7. Same key + same request already completed
        if (idempotencyRecord.getReservation() != null) {

            Reservation existingReservation =
                    idempotencyRecord.getReservation();

            reservationMetrics.idempotentReplay();

            log.info(
                    "idempotent_replay reservation_id={} show_id={} user_id={}",
                    existingReservation.getId(),
                    showId,
                    userId);

            return toReservationResponse(existingReservation);
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

        // 9. Maximum 4 confirmed seats per user/show
        if (userBooking.getConfirmedSeats()
                + requestedSeats > 4) {

            log.warn(
                    "reservation_declined reason=per_user_limit show_id={} user_id={} current_seats={} requested_seats={}",
                    showId,
                    userId,
                    userBooking.getConfirmedSeats(),
                    requestedSeats);

            throw new BookingLimitExceededException(
                    "User cannot reserve more than 4 seats");
        }

        // 10. Lock requested seats.
        // Repository orders them by seatCode to reduce deadlock risk.
        List<Seat> seats =
                seatRepository.findSeatsForUpdate(
                        showId,
                        request.getSeats());

        // 11. Ensure every requested seat exists
        if (seats.size() != request.getSeats().size()) {

            log.warn(
                    "reservation_declined reason=invalid_seat show_id={} user_id={} requested_seats={}",
                    showId,
                    userId,
                    request.getSeats());

            throw new InvalidReservationRequestException(
                    "One or more requested seats do not exist");
        }

        // 12. All-or-nothing availability check
        for (Seat seat : seats) {

            if (seat.getStatus() != SeatStatus.AVAILABLE) {

                log.warn(
                        "reservation_declined reason=seat_taken show_id={} user_id={} seat={}",
                        showId,
                        userId,
                        seat.getSeatCode());

                throw new SeatUnavailableException(
                        "One or more requested seats are already reserved");
            }
        }

        // 13. Create reservation
        Reservation reservation =
                new Reservation(show, userId);

        Reservation savedReservation =
                reservationRepository.save(reservation);

        // 14. Connect reservation with requested seats
        List<ReservationSeat> reservationSeats =
                new ArrayList<>();

        for (Seat seat : seats) {

            seat.setStatus(SeatStatus.CONFIRMED);

            // Track which reservation currently owns the seat
            seat.setCurrentReservation(savedReservation);

            reservationSeats.add(
                    new ReservationSeat(
                            savedReservation,
                            seat));
        }

        reservationSeatRepository.saveAll(reservationSeats);

        // 15. Update user's confirmed-seat count
        userBooking.setConfirmedSeats(
                userBooking.getConfirmedSeats()
                        + seats.size());

        // 16. Store successful reservation against idempotency key
        idempotencyRecord.setReservation(savedReservation);

        // Prometheus metric
        reservationMetrics.reservationConfirmed();

        log.info(
                "reservation_confirmed reservation_id={} show_id={} user_id={} seats={} amount_paise={}",
                savedReservation.getId(),
                showId,
                userId,
                request.getSeats(),
                show.getPricePaise() * seats.size());

        return toReservationResponse(savedReservation);
    }

    @Transactional
    public ReservationResponse cancelReservation(
            Long reservationId,
            String userId) {

        log.info(
                "cancellation_request reservation_id={} user_id={}",
                reservationId,
                userId);

        // 1. Lock reservation
        Reservation reservation =
                reservationRepository
                        .findForUpdate(reservationId)
                        .orElseThrow(() ->
                                new InvalidReservationRequestException(
                                        "Reservation not found"));

        // 2. Only owner can cancel
        if (!reservation.getUserId().equals(userId)) {

            log.warn(
                    "cancellation_declined reason=not_owner reservation_id={} user_id={}",
                    reservationId,
                    userId);

            throw new ReservationAccessDeniedException(
                    "Reservation does not belong to this user");
        }

        // 3. Cancellation is idempotent
        if (reservation.getStatus()
                == ReservationStatus.CANCELLED) {

            log.info(
                    "cancellation_replay reservation_id={} user_id={}",
                    reservationId,
                    userId);

            return toReservationResponse(reservation);
        }

        // 4. Find seats belonging to reservation
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
         * Keep lock order consistent:
         *
         * Reserve:
         * user/show -> seats
         *
         * Cancel:
         * user/show -> seats
         */

        // 5. Lock user/show counter FIRST
        UserShowBooking userBooking =
                userShowBookingRepository
                        .findForUpdate(
                                userId,
                                reservation.getShow().getId())
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "User booking record not found"));

        // 6. Lock seats AFTER user/show counter
        List<Seat> lockedSeats =
                seatRepository.findSeatsForUpdate(
                        reservation.getShow().getId(),
                        seatCodes);

        // 7. Release only seats still owned by this reservation
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

        // 8. Update user's confirmed-seat counter
        userBooking.setConfirmedSeats(
                userBooking.getConfirmedSeats()
                        - releasedSeats);

        // 9. Mark reservation cancelled
        reservation.setStatus(
                ReservationStatus.CANCELLED);

        log.info(
                "reservation_cancelled reservation_id={} show_id={} user_id={} seats={} released_seats={}",
                reservationId,
                reservation.getShow().getId(),
                userId,
                seatCodes,
                releasedSeats);

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