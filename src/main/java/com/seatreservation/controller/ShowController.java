package com.seatreservation.controller;

import com.seatreservation.dto.CreateShowRequest;
import com.seatreservation.dto.ShowStateResponse;
import com.seatreservation.entity.Show;
import com.seatreservation.service.ShowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.seatreservation.dto.ShowStateResponse;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    public ResponseEntity<Show> createShow(
            @Valid @RequestBody CreateShowRequest request) {

        Show show = showService.createShow(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(show);
    }

    @GetMapping("/{showId}")
    public ResponseEntity<ShowStateResponse> getShow(
            @PathVariable Long showId) {

        ShowStateResponse response = showService.getShowState(showId);

        return ResponseEntity.ok(response);
    }
}