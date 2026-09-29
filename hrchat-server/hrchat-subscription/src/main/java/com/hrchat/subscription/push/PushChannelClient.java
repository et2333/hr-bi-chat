package com.hrchat.subscription.push;

/**
 * 推送渠道客户端（生产经 IM 网关/邮件网关，本地以 mock 模拟）。
 */
public interface PushChannelClient {

    /**
     * 推送订阅快照。
     *
     * @param channel      渠道码（1邮件 2企微 3钉钉 4飞书）
     * @param userId       收件人 sec_user.id
     * @param reportId     报表 id
     * @param snapshotUrl  快照访问链接（BR-13 实时鉴权）
     * @return true=推送成功
     */
    boolean send(int channel, Long userId, Long reportId, String snapshotUrl);
}
