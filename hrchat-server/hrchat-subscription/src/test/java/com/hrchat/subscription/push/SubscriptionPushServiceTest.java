package com.hrchat.subscription.push;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hrchat.authz.entity.SecUser;
import com.hrchat.authz.mapper.SecUserMapper;
import com.hrchat.authz.model.UserContext;
import com.hrchat.authz.service.AuthzService;
import com.hrchat.common.exception.BizException;
import com.hrchat.common.error.ErrorCode;
import com.hrchat.report.chart.ChartViews.ChartDataView;
import com.hrchat.report.chart.ChartViews.ChartSeries;
import com.hrchat.report.entity.RptReport;
import com.hrchat.report.entity.RptSubReceiver;
import com.hrchat.report.entity.RptSubscription;
import com.hrchat.report.mapper.RptReportMapper;
import com.hrchat.report.mapper.RptSnapshotMapper;
import com.hrchat.report.mapper.RptSubReceiverMapper;
import com.hrchat.report.mapper.RptSubscriptionMapper;
import com.hrchat.report.service.ReportChartService;
import com.hrchat.report.service.ReportExcelExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订阅推送单测（S5）：到期扫描、BR-13 收件人鉴权、失败重试 ≤3 次、快照生成、阶段3附件与手动触发。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionPushServiceTest {

    @Mock
    private RptSubscriptionMapper subscriptionMapper;
    @Mock
    private RptSubReceiverMapper receiverMapper;
    @Mock
    private RptSnapshotMapper snapshotMapper;
    @Mock
    private RptReportMapper reportMapper;
    @Mock
    private SecUserMapper secUserMapper;
    @Mock
    private AuthzService authzService;
    @Mock
    private PushChannelClient pushChannelClient;
    @Mock
    private ReportChartService chartService;
    @Mock
    private ReportExcelExporter excelExporter;

    @TempDir
    Path tempDir;

    private SubscriptionPushService service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionPushService(subscriptionMapper, receiverMapper, snapshotMapper,
                reportMapper, secUserMapper, authzService, pushChannelClient, chartService, excelExporter);
    }

    private RptSubscription dueSub() {
        RptSubscription sub = new RptSubscription();
        sub.setId(5L);
        sub.setReportId(1L);
        sub.setChannel(1);
        sub.setFreq(1);
        sub.setStatus(1);
        sub.setIsDeleted(0);
        sub.setNextRunAt(LocalDateTime.now().minusMinutes(1));
        return sub;
    }

    private RptReport report(Long ownerId) {
        RptReport r = new RptReport();
        r.setId(1L);
        r.setOwnerId(ownerId);
        r.setIsDeleted(0);
        return r;
    }

    @Test
    void processDue_allowedReceiverPushedAndSnapshotGenerated() {
        RptSubscription sub = dueSub();
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(sub));
        RptSubReceiver receiver = new RptSubReceiver();
        receiver.setId(11L);
        receiver.setSubscriptionId(5L);
        receiver.setUserId(1L);
        when(receiverMapper.selectList(any())).thenReturn(List.of(receiver));
        when(reportMapper.selectById(1L)).thenReturn(report(1L));
        when(pushChannelClient.send(anyInt(), anyLong(), anyLong(), anyString())).thenReturn(true);
        when(receiverMapper.updateById(any())).thenReturn(1);
        when(snapshotMapper.insert(any())).thenReturn(1);
        when(subscriptionMapper.updateById(any())).thenReturn(1);

        LocalDateTime before = sub.getNextRunAt();
        int ok = service.processDueSubscriptions();

        assertEquals(1, ok);
        verify(pushChannelClient).send(anyInt(), anyLong(), anyLong(), anyString());
        verify(snapshotMapper, times(1)).insert(any());
        ArgumentCaptor<RptSubscription> captor = ArgumentCaptor.forClass(RptSubscription.class);
        verify(subscriptionMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getNextRunAt().isAfter(before));
    }

    @Test
    void processDue_deniedReceiverMarkedFailedWithoutPush() {
        RptSubscription sub = dueSub();
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(sub));
        RptSubReceiver receiver = new RptSubReceiver();
        receiver.setId(12L);
        receiver.setSubscriptionId(5L);
        receiver.setUserId(2L);
        when(receiverMapper.selectList(any())).thenReturn(List.of(receiver));
        when(reportMapper.selectById(1L)).thenReturn(report(1L)); // 非所有者
        SecUser hr02 = new SecUser();
        hr02.setId(2L);
        hr02.setEmpNo("hr02");
        when(secUserMapper.selectById(2L)).thenReturn(hr02);
        when(authzService.resolveContext("hr02")).thenReturn(UserContext.builder()
                .userId(2L).empNo("hr02").roles(List.of("PAYROLL")).build());
        doThrow(new BizException(ErrorCode.FUNC_FORBIDDEN))
                .when(authzService).checkFunc(any(), anyString());
        when(receiverMapper.updateById(any())).thenReturn(1);
        when(subscriptionMapper.updateById(any())).thenReturn(1);

        int ok = service.processDueSubscriptions();

        assertEquals(0, ok);
        verify(pushChannelClient, never()).send(anyInt(), anyLong(), anyLong(), anyString());
        verify(receiverMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                r -> r.getPushStatus() == 2));
    }

    @Test
    void pushWithRetry_succeedsOnThirdAttempt() {
        RptSubscription sub = dueSub();
        RptSubReceiver receiver = new RptSubReceiver();
        receiver.setId(11L);
        receiver.setUserId(1L);
        when(pushChannelClient.send(anyInt(), anyLong(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("channel down"))
                .thenThrow(new RuntimeException("channel down"))
                .thenReturn(true);

        boolean pushed = service.pushWithRetry(sub, receiver);

        assertTrue(pushed);
        verify(pushChannelClient, times(3)).send(anyInt(), anyLong(), anyLong(), anyString());
    }

    @Test
    void pushWithRetry_givesUpAfterMaxRetries() {
        RptSubscription sub = dueSub();
        RptSubReceiver receiver = new RptSubReceiver();
        receiver.setId(11L);
        receiver.setUserId(1L);
        when(pushChannelClient.send(anyInt(), anyLong(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("channel down"));

        boolean pushed = service.pushWithRetry(sub, receiver);

        assertFalse(pushed);
        verify(pushChannelClient, times(SubscriptionPushService.MAX_RETRIES))
                .send(anyInt(), anyLong(), anyLong(), anyString());
    }

    @Test
    void ownerAlwaysAllowedByBr13() {
        when(reportMapper.selectById(1L)).thenReturn(report(1L));
        assertTrue(service.isReceiverAllowed(1L, 1L));
    }

    @Test
    void generateAttachment_writesXlsxToOutputDirWithLog() throws Exception {
        service.setOutputDir(tempDir.toString());
        RptSubscription sub = dueSub();
        RptReport r = report(1L);
        r.setReportName("六月月报");
        when(reportMapper.selectById(1L)).thenReturn(r);
        when(chartService.chartDataForExport(anyLong(), any())).thenReturn(new ChartDataView(
                "BAR", List.of("研发一部", "研发二部"), List.of(new ChartSeries("在职人数", List.of(10, 5))),
                List.of()));
        when(excelExporter.export(anyString(), anyString(), any(), any())).thenReturn(
                new byte[]{'P', 'K', 3, 4});

        Logger logger = (Logger) LoggerFactory.getLogger(SubscriptionPushService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String path = service.generateAttachment(sub);

            assertNotNull(path);
            Path file = Path.of(path);
            assertTrue(Files.exists(file));
            assertTrue(file.getFileName().toString().startsWith("report-1-"));
            assertTrue(file.getFileName().toString().endsWith(".xlsx"));
            assertEquals(4, Files.size(file));
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.INFO
                    && e.getMessage().contains("订阅推送附件已生成")));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void generateAttachment_skippedWhenOutputDirNotConfigured() {
        RptSubscription sub = dueSub();
        assertTrue(service.generateAttachment(sub) == null
                || !service.generateAttachment(sub).startsWith("report"));
    }

    @Test
    void generateAttachment_fallsBackToNoChartData() throws Exception {
        service.setOutputDir(tempDir.toString());
        RptSubscription sub = dueSub();
        RptReport r = report(1L);
        r.setReportName("月报");
        when(reportMapper.selectById(1L)).thenReturn(r);
        when(chartService.chartDataForExport(anyLong(), any())).thenReturn(null);
        when(excelExporter.export(anyString(), anyString(), any(), any())).thenReturn(
                new byte[]{'P', 'K', 3, 4});

        String path = service.generateAttachment(sub);

        assertNotNull(path);
        assertTrue(Files.exists(Path.of(path)));
    }

    @Test
    void triggerPush_countsPushedAndSkipped() {
        service.setOutputDir("");
        RptSubscription sub = dueSub();
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(sub));
        RptSubReceiver receiver = new RptSubReceiver();
        receiver.setId(11L);
        receiver.setSubscriptionId(5L);
        receiver.setUserId(1L);
        when(receiverMapper.selectList(any())).thenReturn(List.of(receiver));
        when(reportMapper.selectById(1L)).thenReturn(report(1L));
        when(pushChannelClient.send(anyInt(), anyLong(), anyLong(), anyString())).thenReturn(true);
        when(receiverMapper.updateById(any())).thenReturn(1);
        when(subscriptionMapper.updateById(any())).thenReturn(1);

        SubscriptionPushService.PushTriggerResult result = service.triggerPush(1L);

        assertEquals(1, result.pushed());
        assertEquals(0, result.skipped());
        verify(pushChannelClient).send(anyInt(), anyLong(), anyLong(), anyString());
    }

    @Test
    void triggerPush_allDeniedMarkedSkipped() {
        service.setOutputDir("");
        RptSubscription sub = dueSub();
        when(subscriptionMapper.selectList(any())).thenReturn(List.of(sub));
        RptSubReceiver receiver = new RptSubReceiver();
        receiver.setId(12L);
        receiver.setSubscriptionId(5L);
        receiver.setUserId(2L);
        when(receiverMapper.selectList(any())).thenReturn(List.of(receiver));
        when(reportMapper.selectById(1L)).thenReturn(report(1L));
        SecUser hr02 = new SecUser();
        hr02.setId(2L);
        hr02.setEmpNo("hr02");
        when(secUserMapper.selectById(2L)).thenReturn(hr02);
        when(authzService.resolveContext("hr02")).thenReturn(UserContext.builder()
                .userId(2L).empNo("hr02").roles(List.of("PAYROLL")).build());
        doThrow(new BizException(ErrorCode.FUNC_FORBIDDEN))
                .when(authzService).checkFunc(any(), anyString());
        when(receiverMapper.updateById(any())).thenReturn(1);
        when(subscriptionMapper.updateById(any())).thenReturn(1);

        SubscriptionPushService.PushTriggerResult result = service.triggerPush(null);

        assertEquals(0, result.pushed());
        assertEquals(1, result.skipped());
        verify(pushChannelClient, never()).send(anyInt(), anyLong(), anyLong(), anyString());
    }
}
