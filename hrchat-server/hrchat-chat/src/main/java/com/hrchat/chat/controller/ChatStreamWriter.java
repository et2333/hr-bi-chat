package com.hrchat.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.sse.SseEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;

/** One sequence for live progress and persisted terminal frames. Disconnects do not erase results. */
final class ChatStreamWriter implements Consumer<SseEvent> {
    private final OutputStream output;
    private final ObjectMapper mapper;
    private int sequence;
    private boolean disconnected;
    ChatStreamWriter(OutputStream output, ObjectMapper mapper) { this.output = output; this.mapper = mapper; }
    @Override public synchronized void accept(SseEvent event) {
        if (disconnected) return;
        try {
            Map<String, Object> frame = Map.of("seq", ++sequence, "event", event.event(),
                    "ts", OffsetDateTime.now().toString(), "payload", event.payload());
            output.write(("event: " + event.event() + "\ndata: " + mapper.writeValueAsString(frame) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ex) { disconnected = true; }
    }
    @SuppressWarnings("unchecked")
    void terminal(String body) throws IOException {
        for (String line : body.split("\\r?\\n")) if (line.startsWith("data:")) {
            Map<String, Object> frame = mapper.readValue(line.substring(5).trim(), Map.class);
            if (!"HEARTBEAT".equals(frame.get("event")))
                accept(new SseEvent(String.valueOf(frame.get("event")), (Map<String, Object>) frame.get("payload")));
        }
    }
}
