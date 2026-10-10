package com.hrchat.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrchat.chat.entity.ChtTurn;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Display snapshots only; never used to authorize or execute a historical option. */
public final class InteractionHistory {
    private InteractionHistory() { }
    private static final ObjectMapper JSON = new ObjectMapper();

    public static Map<String, Object> envelope(ChtTurn turn) {
        if (turn == null || turn.getInheritJson() == null) return new LinkedHashMap<>();
        try { return JSON.readValue(turn.getInheritJson(), new TypeReference<LinkedHashMap<String, Object>>() { }); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored interaction history", e); }
    }

    public static List<Map<String, Object>> read(ChtTurn turn) {
        Object value = envelope(turn).get("interaction_history");
        if (!(value instanceof List<?>)) return List.of();
        return JSON.convertValue(value, new TypeReference<List<Map<String, Object>>>() { });
    }

    public static void append(ChtTurn turn, String kind, Map<String, Object> fields) {
        Map<String, Object> envelope = envelope(turn);
        List<Map<String, Object>> history = new ArrayList<>(read(turn));
        Map<String, Object> entry = new LinkedHashMap<>(fields);
        entry.put("kind", kind);
        entry.put("sequence", history.size() + 1);
        entry.put("at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        history.add(entry);
        envelope.put("interaction_history", history);
        envelope.putIfAbsent("created_at", entry.get("at"));
        try { turn.setInheritJson(JSON.writeValueAsString(envelope)); }
        catch (Exception e) { throw new IllegalStateException("Cannot save interaction history", e); }
    }
}
