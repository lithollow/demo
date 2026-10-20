package com.example.aicsupport.intent;

import com.example.aicsupport.domain.Intent;
import com.example.aicsupport.domain.ToolName;
import com.example.aicsupport.plan.Plan;
import com.example.aicsupport.plan.TaskPlanner;
import com.example.aicsupport.state.ConversationState;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridIntentClassifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuleBasedIntentClassifier rules = new RuleBasedIntentClassifier();

    @Test
    void skipsLlmWhenRuleConfidenceIsSufficient() {
        AtomicInteger calls = new AtomicInteger();
        HybridIntentClassifier classifier = hybrid(model(calls, request -> validOrderJson()), calls);

        IntentResult result = classifier.classify(
                "我的订单 ORD-1001 到哪了？", ConversationState.empty());

        assertThat(result.source()).isEqualTo("RULE");
        assertThat(result.intent()).isEqualTo(Intent.ORDER_STATUS);
        assertThat(calls).hasValue(0);
    }

    @Test
    void fallsBackToRulesWhenLlmReturnsInvalidJson() {
        AtomicInteger calls = new AtomicInteger();
        HybridIntentClassifier classifier = hybrid(model(calls, request -> "not-json"), calls);

        IntentResult result = classifier.classify("量子火车", ConversationState.empty());

        assertThat(calls).hasValue(1);
        assertThat(result.source()).isEqualTo("RULE");
        assertThat(result.intent()).isEqualTo(Intent.UNKNOWN);
    }

    @Test
    void fallsBackToRulesWhenLlmReturnsUnknownIntent() {
        AtomicInteger calls = new AtomicInteger();
        HybridIntentClassifier classifier = hybrid(model(calls,
                request -> """
                        {"intent":"DROP_TABLE","confidence":0.99}
                        """), calls);

        IntentResult result = classifier.classify("量子火车", ConversationState.empty());

        assertThat(calls).hasValue(1);
        assertThat(result.source()).isEqualTo("RULE");
        assertThat(result.intent()).isEqualTo(Intent.UNKNOWN);
    }

    @Test
    void filtersInvalidLlmSlotsBeforePlanning() {
        LlmIntentClassifier classifier = new LlmIntentClassifier(model(new AtomicInteger(),
                request -> """
                        {"intent":"ORDER_STATUS","confidence":0.91,
                         "orderId":"ORD-1","ticketId":"TCK-999999999999999999999999999999",
                         "refundAmount":"1.234","refundReason":null,"requiresHuman":false}
                        """), objectMapper);

        IntentResult result = classifier.classify("帮我看看", ConversationState.empty());

        assertThat(result.intent()).isEqualTo(Intent.ORDER_STATUS);
        assertThat(result.slots()).doesNotContainKeys("orderId", "ticketId", "refundAmount");
        assertThat(result.requiresHuman()).isFalse();
    }

    @Test
    void convertsNonHumanIntentWithHumanSignalIntoHandoffPlan() {
        IntentResult intent = new IntentResult(
                Intent.ORDER_STATUS, 0.93, Map.of("orderId", "ORD-1001"), "LLM", true);

        Plan plan = new TaskPlanner().plan(intent, ConversationState.empty());

        assertThat(plan.reason()).isEqualTo("HUMAN_REQUIRED");
        assertThat(plan.requiresHuman()).isTrue();
        assertThat(plan.steps()).singleElement()
                .satisfies(step -> assertThat(step.tool()).isEqualTo(ToolName.HUMAN_HANDOFF));
    }

    private HybridIntentClassifier hybrid(
            ChatModel model,
            AtomicInteger calls) {
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmIntentClassifier> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable())
                .thenReturn(new LlmIntentClassifier(model, objectMapper));
        return new HybridIntentClassifier(rules, provider);
    }

    private ChatModel model(AtomicInteger calls, Function<ChatRequest, String> response) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                calls.incrementAndGet();
                return ChatResponse.builder()
                        .aiMessage(AiMessage.from(response.apply(request)))
                        .build();
            }
        };
    }

    private String validOrderJson() {
        return """
                {"intent":"ORDER_STATUS","confidence":0.97,
                 "orderId":"ORD-1001","ticketId":null,"refundAmount":null,
                 "refundReason":null,"requiresHuman":false}
                """;
    }
}
