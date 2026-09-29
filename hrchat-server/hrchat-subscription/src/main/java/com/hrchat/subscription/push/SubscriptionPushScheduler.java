package com.hrchat.subscription.push;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订阅调度器：周期扫描到期订阅（XXL-Job 的本地 @Scheduled 形态）。
 *
 * <p>扫描间隔可配 {@code hrchat.subscription.scan-interval-ms}，默认 60s。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionPushScheduler {

    private final SubscriptionPushService pushService;

    @Scheduled(fixedDelayString = "${hrchat.subscription.scan-interval-ms:60000}")
    public void scanDueSubscriptions() {
        int pushed = pushService.processDueSubscriptions();
        if (pushed > 0) {
            log.info("订阅调度完成: 成功推送收件人次数={}", pushed);
        }
    }
}
