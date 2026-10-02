package com.eduplatform.operation.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 公告Outbox持久发布；mandatory+confirm都成功才PUBLISHED，有限退避耗尽FAILED，不回滚公告业务事实。 */
@Component
@ConditionalOnProperty(name="platform.operation.messaging-enabled",havingValue="true")
public class OperationOutboxPublisher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transactions;
    private final int maxAttempts;
    private final long timeoutMs;
    public OperationOutboxPublisher(JdbcTemplate jdbc,RabbitTemplate rabbit,PlatformTransactionManager manager,
            @Value("${OPERATION_OUTBOX_MAX_ATTEMPTS:20}") int maxAttempts,
            @Value("${OPERATION_OUTBOX_CONFIRM_TIMEOUT_MS:3000}") long timeoutMs) {
        this.jdbc=jdbc; this.rabbit=rabbit; this.transactions=new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.maxAttempts=maxAttempts; this.timeoutMs=timeoutMs; rabbit.setMandatory(true);
        if(maxAttempts<1 || maxAttempts>20 || timeoutMs<100 || timeoutMs>10000) throw new IllegalArgumentException("Invalid outbox limits");
    }
    /** 每轮最多50条，逐事件事务锁防并发领取；单条失败记录安全异常类型继续其他条，不领取FAILED。 */
    @Scheduled(fixedDelayString="${OPERATION_OUTBOX_POLL_DELAY:2s}")
    public void publishPending() {
        var ids=jdbc.query("SELECT event_id FROM operation_outbox_event WHERE status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,event_id LIMIT 50",(rs,n)->rs.getString(1),Timestamp.from(Instant.now()));
        for(String id:ids) publish(id);
    }
    /** 输入持久事件ID；ACK且无return才落发布时间，断线/超时/NACK/return有限退避，可能重复运输由eventId处理。 */
    public void publish(String id) {
        transactions.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT * FROM operation_outbox_event WHERE event_id=? FOR UPDATE",id);
            if(rows.isEmpty()) return; var row=rows.get(0);
            if(!"PENDING".equals(row.get("status")) || ((Timestamp)row.get("next_attempt_at")).toInstant().isAfter(Instant.now())) return;
            var correlation=new CorrelationData(id);
            MessageProperties headers=new MessageProperties(); headers.setContentType("application/json");
            headers.setContentEncoding("UTF-8"); headers.setMessageId(id); headers.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            try {
                rabbit.send(NotificationRabbitConfig.EXCHANGE,"operation.announcement.changed.v1",new Message(row.get("payload_json").toString().getBytes(StandardCharsets.UTF_8),headers),correlation);
                var confirm=correlation.getFuture().get(timeoutMs,TimeUnit.MILLISECONDS);
                if(!confirm.ack() || correlation.getReturned()!=null) throw new IllegalStateException("Publish rejected");
                jdbc.update("UPDATE operation_outbox_event SET status='PUBLISHED',published_at=?,last_error=NULL WHERE event_id=?",Timestamp.from(Instant.now()),id);
            } catch(Exception failure) {
                if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
                int attempt=((Number)row.get("attempts")).intValue()+1;
                jdbc.update("UPDATE operation_outbox_event SET status=?,attempts=?,next_attempt_at=?,last_error=? WHERE event_id=?",attempt>=maxAttempts?"FAILED":"PENDING",attempt,Timestamp.from(Instant.now().plusSeconds(Math.min(300,1L<<Math.min(attempt,8)))),failure.getClass().getSimpleName(),id);
            }
        });
    }
}
