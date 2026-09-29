package com.hrchat.subscription.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订阅调度开关（模块内 @EnableScheduling，随主应用组件扫描装配）。
 */
@Configuration
@EnableScheduling
public class SubscriptionSchedulingConfig {
}
