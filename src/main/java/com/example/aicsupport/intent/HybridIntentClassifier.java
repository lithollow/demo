package com.example.aicsupport.intent;

import com.example.aicsupport.state.ConversationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class HybridIntentClassifier implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(HybridIntentClassifier.class);
    private final RuleBasedIntentClassifier rules;
    private final ObjectProvider<LlmIntentClassifier> llmProvider;

    public HybridIntentClassifier(
            RuleBasedIntentClassifier rules,
            ObjectProvider<LlmIntentClassifier> llmProvider) {
        this.rules = rules;
        this.llmProvider = llmProvider;
    }

    @Override
    public IntentResult classify(String message, ConversationState state) {
        IntentResult ruleResult = rules.classify(message, state);
        if (ruleResult.confidence() >= 0.65 || state.pendingAction() != null) {
            return ruleResult;
        }
        LlmIntentClassifier llm = llmProvider.getIfAvailable();
        if (llm == null) {
            return ruleResult;
        }
        try {
            IntentResult result = llm.classify(message, state);
            return result.confidence() >= ruleResult.confidence() ? result : ruleResult;
        } catch (RuntimeException exception) {
            log.warn("LangChain4j intent classification failed; using rule fallback: {}",
                    exception.getClass().getSimpleName());
            return ruleResult;
        }
    }
}

