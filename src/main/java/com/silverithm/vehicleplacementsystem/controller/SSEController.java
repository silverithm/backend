package com.silverithm.vehicleplacementsystem.controller;

import com.silverithm.vehicleplacementsystem.service.SSEService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/SSE")
@RequiredArgsConstructor
public class SSEController {
    private final SSEService sseService;

    @GetMapping(value = "/subscribe/{userName}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable String userName) {
        return sseService.subscribe(userName);
    }

    @PostMapping("/send-progress/{userName}")
    public void sendProgress(@PathVariable String userName) {
        sseService.notify(userName, "progress");
    }


}