package com.example.aicsupport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private final Llm llm = new Llm();
    private final CustomerService customerService = new CustomerService();

    public Llm getLlm() {
        return llm;
    }

    public CustomerService getCustomerService() {
        return customerService;
    }

    public static class Llm {
        private boolean enabled;
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private String modelName = "gpt-4.1-mini";
        private Duration timeout = Duration.ofSeconds(20);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class CustomerService {
        private int maxMessageLength = 2000;
        private BigDecimal refundReviewThreshold = new BigDecimal("500.00");

        public int getMaxMessageLength() {
            return maxMessageLength;
        }

        public void setMaxMessageLength(int maxMessageLength) {
            this.maxMessageLength = maxMessageLength;
        }

        public BigDecimal getRefundReviewThreshold() {
            return refundReviewThreshold;
        }

        public void setRefundReviewThreshold(BigDecimal refundReviewThreshold) {
            this.refundReviewThreshold = refundReviewThreshold;
        }
    }
}

