package com.seatreservation.service;

import com.seatreservation.dto.CreateShowRequest;
import com.seatreservation.entity.Seat;
import com.seatreservation.entity.Show;
import com.seatreservation.repository.SeatRepository;
import com.seatreservation.repository.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
                request.getPricePaise()
        );

        Show savedShow = showRepository.save(show);

        List<Seat> seats = new ArrayList<>();

        for (int i = 1; i <= request.getSeats(); i++) {
            seats.add(new Seat(i, savedShow));
        }

        seatRepository.saveAll(seats);

        return savedShow;
    }
}