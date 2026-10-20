package com.example.aicsupport.state;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

public record ConversationState(
        Map<String, Object> slots,
        String lastQuestion,
        String pendingAction,
        int unknownTurns) {

    public static ConversationState empty() {
        return new ConversationState(Map.of(), null, null, 0);
    }

    public ConversationState {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }

    public Map<String, Object> mergedSlots(Map<String, Object> updates) {
        Map<String, Object> merged = new LinkedHashMap<>(slots);
        if (updates != null) {
            updates.forEach((key, value) -> {
                if (value != null) {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }

    public ConversationState withSlots(Map<String, Object> newSlots) {
        return new ConversationState(newSlots, lastQuestion, pendingAction, unknownTurns);
    }

    public ConversationState withoutSlots(String... names) {
        Map<String, Object> remaining = new LinkedHashMap<>(slots);
        Arrays.stream(names).forEach(remaining::remove);
        return withSlots(remaining);
    }

    public ConversationState withLastQuestion(String question) {
        return new ConversationState(slots, question, pendingAction, unknownTurns);
    }

    public ConversationState withPendingAction(String action) {
        return new ConversationState(slots, lastQuestion, action, unknownTurns);
    }

    public ConversationState withUnknownTurns(int turns) {
        return new ConversationState(slots, lastQuestion, pendingAction, turns);
    }

    public String stringSlot(String name) {
        Object value = slots.get(name);
        return value == null ? null : value.toString();
    }

    public boolean booleanSlot(String name) {
        Object value = slots.get(name);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}

