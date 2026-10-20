package com.example.aicsupport.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class LangChain4jConfig {

    @Bean
    @ConditionalOnProperty(prefix = "ai.llm", name = "enabled", havingValue = "true")
    ChatModel chatModel(AiProperties properties) {
        AiProperties.Llm llm = properties.getLlm();
        if (!StringUtils.hasText(llm.getApiKey())) {
            throw new IllegalStateException(
                    "AI_LLM_ENABLED=true but OPENAI_API_KEY is empty; disable LLM mode or provide a key");
        }
        return OpenAiChatModel.builder()
                .apiKey(llm.getApiKey())
                .baseUrl(llm.getBaseUrl())
                .modelName(llm.getModelName())
                .temperature(0.0)
                .timeout(llm.getTimeout())
                .build();
    }
}

