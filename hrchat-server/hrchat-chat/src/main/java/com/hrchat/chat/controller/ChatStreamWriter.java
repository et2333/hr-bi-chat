package com.hrchat.chat.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.api.sse.SseEvent;
import com.hrchat.api.sse.SseEvents;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** One sequence for live progress and persisted terminal frames. Disconnects do not erase results. */
final class ChatStreamWriter implements Consumer<SseEvent> {
    private static final ZoneId TS_ZONE = ZoneId.of("Asia/Shanghai");

    private final OutputStream output;
    private final ObjectMapper mapper;
    private int sequence;
    private boolean disconnected;

    ChatStreamWriter(OutputStream output, ObjectMapper mapper) {
        this.output = output;
        this.mapper = mapper;
    }

    /** Contract opener: HEARTBEAT with seq=-1 before any semantic frame. */
    synchronized void heartbeat() {
        writeFrame(-1, SseEvents.HEARTBEAT, Map.of());
    }

    @Override
    public synchronized void accept(SseEvent event) {
        writeFrame(++sequence, event.event(), event.payload());
    }

    @SuppressWarnings("unchecked")
    void terminal(String body) throws IOException {
        for (String line : body.split("\\r?\\n")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            Map<String, Object> frame = mapper.readValue(line.substring(5).trim(), Map.class);
            if (SseEvents.HEARTBEAT.equals(frame.get("event"))) {
                continue;
            }
            accept(new SseEvent(String.valueOf(frame.get("event")), (Map<String, Object>) frame.get("payload")));
        }
    }

    private void writeFrame(int seq, String event, Map<String, Object> payload) {
        if (disconnected) {
            return;
        }
        try {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("seq", seq);
            frame.put("event", event);
            frame.put("ts", OffsetDateTime.now(TS_ZONE).toString());
            frame.put("payload", payload);
            output.write(("event: " + event + "\ndata: " + mapper.writeValueAsString(frame) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException ex) {
            disconnected = true;
        }
    }
}
