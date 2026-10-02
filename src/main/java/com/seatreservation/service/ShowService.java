package com.seatreservation.service;

import com.seatreservation.dto.CreateShowRequest;
import com.seatreservation.entity.Seat;
import com.seatreservation.entity.Show;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.seatreservation.dto.SeatStateResponse;
import com.seatreservation.dto.ShowStateResponse;
import com.seatreservation.entity.SeatStatus;
import com.seatreservation.exception.ShowNotFoundException;
import java.util.ArrayList;
import java.util.List;

@Service
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;

    public ShowService(
            ShowRepository showRepository,
            SeatRepository seatRepository) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional
    public Show createShow(CreateShowRequest request) {

        Show show = new Show(
                request.getName(),
                request.getSeats(),
                request.getPricePaise());

        Show savedShow = showRepository.save(show);

        List<Seat> seats = new ArrayList<>();

        for (int i = 1; i <= request.getSeats(); i++) {
            seats.add(new Seat(i, savedShow));
        }

        seatRepository.saveAll(seats);

        return savedShow;
    }

    @Transactional(readOnly = true)
    public ShowStateResponse getShowState(Long showId) {

        // 1. Check show exists
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new ShowNotFoundException("Show not found"));

        // 2. Read all seats for this show
        List<Seat> seats = seatRepository.findByShowId(showId);

        int available = 0;
        int confirmed = 0;

        List<SeatStateResponse> seatStates = new ArrayList<>();

        // 3. Calculate counts from the SAME seat snapshot
        for (Seat seat : seats) {

            if (seat.getStatus() == SeatStatus.AVAILABLE) {
                available++;
            } else if (seat.getStatus() == SeatStatus.CONFIRMED) {
                confirmed++;
            }

            seatStates.add(
                    new SeatStateResponse(
                            seat.getSeatNumber(),
                            seat.getStatus().name()));
        }

        // We chose immediate confirmation, not temporary holds
        int held = 0;

        // 4. Safety check for our reconciliation invariant
        if (available + held + confirmed != seats.size()) {
            throw new IllegalStateException(
                    "Seat reconciliation invariant violated");
        }

        return new ShowStateResponse(
                show.getId(),
                show.getName(),
                seats.size(),
                available,
                held,
                confirmed,
                seatStates);
    }
}