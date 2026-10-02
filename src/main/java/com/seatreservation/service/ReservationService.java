package com.seatreservation.service;

import com.seatreservation.repository.ReservationRepository;
import com.seatreservation.repository.ReservationSeatRepository;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import com.seatreservation.repository.UserShowBookingRepository;
import org.springframework.stereotype.Service;
import com.seatreservation.dto.ReserveSeatsRequest;
import com.seatreservation.entity.Seat;
import com.seatreservation.entity.Show;
import org.springframework.transaction.annotation.Transactional;
import com.seatreservation.entity.UserShowBooking;
import com.seatreservation.entity.Seat;
import com.seatreservation.entity.SeatStatus;
import com.seatreservation.entity.Reservation;
import com.seatreservation.entity.ReservationSeat;
import com.seatreservation.exception.SeatUnavailableException;
import com.seatreservation.exception.BookingLimitExceededException;
import com.seatreservation.exception.InvalidReservationRequestException;
import com.seatreservation.exception.ShowNotFoundException;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;

import java.util.List;

@Service
public class ReservationService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final UserShowBookingRepository userShowBookingRepository;

    public ReservationService(
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            ReservationSeatRepository reservationSeatRepository,
            UserShowBookingRepository userShowBookingRepository) {

        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.userShowBookingRepository = userShowBookingRepository;
    }

    @Transactional
    public Reservation reserveSeats(
            Long showId,
            String userId,
            ReserveSeatsRequest request) {

        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ShowNotFoundException("Show not found"));

        Set<Integer> uniqueSeatNumbers = new HashSet<>(request.getSeats());

        if (uniqueSeatNumbers.size() != request.getSeats().size()) {
            throw new InvalidReservationRequestException(
                    "Duplicate seat numbers are not allowed");
        }

        userShowBookingRepository.createIfNotExists(userId, showId);

        UserShowBooking userBooking = userShowBookingRepository
                .findForUpdate(userId, showId)
                .orElseThrow(() -> new IllegalStateException(
                        "User booking record not found"));

        int requestedSeats = request.getSeats().size();

        if (userBooking.getConfirmedSeats() + requestedSeats > 4) {
            throw new BookingLimitExceededException(
                    "User cannot reserve more than 4 seats");
        }

        List<Seat> seats = seatRepository.findSeatsForUpdate(
                showId,
                request.getSeats());

        if (seats.size() != request.getSeats().size()) {
            throw new InvalidReservationRequestException(
                    "One or more requested seats do not exist");
        }

        for (Seat seat : seats) {
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new SeatUnavailableException(
                        "One or more requested seats are already reserved");
            }
        }
        // Create the reservation
        Reservation reservation = new Reservation(show, userId);
        Reservation savedReservation = reservationRepository.save(reservation);

        // Connect the reservation with all requested seats
        List<ReservationSeat> reservationSeats = new ArrayList<>();

        for (Seat seat : seats) {

            seat.setStatus(SeatStatus.CONFIRMED);

            reservationSeats.add(
                    new ReservationSeat(savedReservation, seat));
        }

        // Save reservation-seat mappings
        reservationSeatRepository.saveAll(reservationSeats);

        // Update user's confirmed seat count
        userBooking.setConfirmedSeats(
                userBooking.getConfirmedSeats() + seats.size());

        return savedReservation;
    }

}