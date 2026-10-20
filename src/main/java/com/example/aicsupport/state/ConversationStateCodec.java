package com.example.aicsupport.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ConversationStateCodec {

    private final ObjectMapper objectMapper;

    public ConversationStateCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(ConversationState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize conversation state", exception);
        }
    }

    public ConversationState decode(String json) {
        if (json == null || json.isBlank()) {
            return ConversationState.empty();
        }
        try {
            return objectMapper.readValue(json, ConversationState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize conversation state", exception);
        }
    }
}

