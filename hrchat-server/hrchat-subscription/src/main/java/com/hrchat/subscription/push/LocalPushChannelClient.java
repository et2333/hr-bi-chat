package com.hrchat.subscription.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本地推送 mock（local profile）：记录日志并恒成功，零外部依赖。
 *
 * <p>prod 由 {@code ImPushChannelClient}/{@code MailPushChannelClient} 实现 IM/邮件真实发送。</p>
 */
@Slf4j
@Component
public class LocalPushChannelClient implements PushChannelClient {

    @Override
    public boolean send(int channel, Long userId, Long reportId, String snapshotUrl) {
        log.info("[订阅推送 mock] channel={}, userId={}, reportId={}, snapshotUrl={}",
                channel, userId, reportId, snapshotUrl);
        return true;
    }
}
