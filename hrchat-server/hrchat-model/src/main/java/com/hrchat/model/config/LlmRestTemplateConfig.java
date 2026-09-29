package com.hrchat.model.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * LLM 模块 RestTemplate 装配：部署调用 5s 超时，健康检查 2s 超时。
 */
@Configuration
public class LlmRestTemplateConfig {

    @Bean
    @Qualifier("llmDeployRestTemplate")
    public RestTemplate llmDeployRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    @Bean
    @Qualifier("llmHealthRestTemplate")
    public RestTemplate llmHealthRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        return new RestTemplate(factory);
    }
}
