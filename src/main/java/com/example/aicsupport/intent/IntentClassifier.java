package com.example.aicsupport.intent;

import com.example.aicsupport.state.ConversationState;

public interface IntentClassifier {

    IntentResult classify(String message, ConversationState state);
}

