package com.deepthinktech.bankstmt_audit.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * The no-message-loss contract of the SQS consumer: a message is deleted from the queue only
 * after a durable insert (or an idempotent duplicate); a parse failure or an insert failure
 * leaves it on the queue so it flows to the DLQ. The poll loop and lifecycle are exercised too.
 *
 * <p>The private {@code handle}/{@code pollLoop} methods and the {@code running} flag are reached
 * by reflection so each branch runs synchronously and deterministically, without spawning the
 * real poller threads (the lifecycle test covers those).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditSqsConsumerTest {

    private static final String QUEUE = "https://sqs.test/queue";

    @Mock SqsClient sqs;
    @Mock AuditInsertService insertService;

    private AuditSqsConsumer consumer;

    private static AuditIngestProperties props(boolean enabled, String queueUrl) {
        // concurrency 1 keeps the lifecycle test single-threaded.
        return new AuditIngestProperties(queueUrl, enabled, 1, 20, 10, 60);
    }

    @BeforeEach
    void setUp() {
        consumer = new AuditSqsConsumer(sqs, insertService, props(true, QUEUE));
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
    }

    // --- reflection helpers ----------------------------------------------------------------

    private void setRunning(boolean value) throws Exception {
        Field f = AuditSqsConsumer.class.getDeclaredField("running");
        f.setAccessible(true);
        ((AtomicBoolean) f.get(consumer)).set(value);
    }

    private void invokeHandle(Message m) throws Exception {
        Method handle = AuditSqsConsumer.class.getDeclaredMethod("handle", Message.class);
        handle.setAccessible(true);
        handle.invoke(consumer, m);
    }

    private void invokePollLoop() throws Exception {
        Method loop = AuditSqsConsumer.class.getDeclaredMethod("pollLoop");
        loop.setAccessible(true);
        loop.invoke(consumer);
    }

    private static Message msg(String body) {
        return Message.builder().messageId("m-1").receiptHandle("rh-1").body(body).build();
    }

    private static final String VALID_BODY = "{\"eventId\":\"e1\",\"action\":\"POST /api/x\"}";

    // --- handle(): the delete contract -----------------------------------------------------

    @Test
    void deletesTheMessageAfterASuccessfulInsert() throws Exception {
        when(insertService.insert(any())).thenReturn(true);

        invokeHandle(msg(VALID_BODY));

        verify(insertService).insert(any(AuditMessage.class));
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void deletesTheMessageOnAnIdempotentDuplicate() throws Exception {
        when(insertService.insert(any())).thenReturn(false); // already present

        invokeHandle(msg(VALID_BODY));

        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void keepsTheMessageOnTheQueueWhenTheInsertFails() throws Exception {
        when(insertService.insert(any())).thenThrow(new RuntimeException("DB down"));

        invokeHandle(msg(VALID_BODY));

        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void leavesAPoisonMessageOnTheQueueForTheDlq() throws Exception {
        invokeHandle(msg("this is not json"));

        verify(insertService, never()).insert(any());
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void buildsTheDeleteRequestFromTheMessageReceiptHandle() throws Exception {
        when(insertService.insert(any())).thenReturn(true);

        invokeHandle(msg(VALID_BODY));

        var captor = org.mockito.ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs).deleteMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE);
        assertThat(captor.getValue().receiptHandle()).isEqualTo("rh-1");
    }

    // --- pollLoop(): drains a batch then exits when stopped --------------------------------

    @Test
    void pollLoopHandlesEachReceivedMessageThenExitsWhenStopped() throws Exception {
        when(insertService.insert(any())).thenReturn(true);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder().messages(List.of(msg(VALID_BODY))).build())
            .thenAnswer(inv -> {
                setRunning(false); // second poll: stop the loop and return an empty batch
                return ReceiveMessageResponse.builder().messages(List.of()).build();
            });

        setRunning(true);
        invokePollLoop();

        verify(insertService).insert(any(AuditMessage.class));
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void pollLoopBreaksWithoutCrashingWhenReceiveFailsAfterStop() throws Exception {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(inv -> {
            setRunning(false); // already stopping when the transient error arrives
            throw new RuntimeException("transient SQS error");
        });

        setRunning(true);
        assertThatCode(this::invokePollLoop).doesNotThrowAnyException();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void pollLoopStopsMidBatchWhenAskedToShutDown() throws Exception {
        // Two messages in the batch; the first insert flips the running flag off, so the loop
        // must abandon the rest of the batch immediately rather than process the second.
        when(insertService.insert(any())).thenAnswer(inv -> {
            setRunning(false);
            return true;
        });
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder()
                .messages(List.of(msg(VALID_BODY), msg(VALID_BODY))).build());

        setRunning(true);
        invokePollLoop();

        verify(insertService, times(1)).insert(any(AuditMessage.class)); // only the first
    }

    @Test
    void pollLoopBacksOffAndRetriesAfterATransientReceiveError() throws Exception {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenThrow(new RuntimeException("throttled"))       // 1st: transient error -> back off
            .thenAnswer(inv -> {                                 // 2nd: succeed, then stop
                setRunning(false);
                return ReceiveMessageResponse.builder().messages(List.of()).build();
            });

        setRunning(true);
        assertThatCode(this::invokePollLoop).doesNotThrowAnyException();
        verify(sqs, times(2)).receiveMessage(any(ReceiveMessageRequest.class)); // retried, didn't exit
    }

    // --- start() guards --------------------------------------------------------------------

    @Test
    void doesNotPollWhenDisabled() {
        AuditSqsConsumer disabled = new AuditSqsConsumer(sqs, insertService, props(false, QUEUE));
        disabled.start();
        verifyNoInteractions(sqs);
    }

    @Test
    void doesNotPollWhenQueueUrlIsBlank() {
        AuditSqsConsumer noUrl = new AuditSqsConsumer(sqs, insertService, props(true, "  "));
        noUrl.start();
        verifyNoInteractions(sqs);
    }

    // --- start()/stop() lifecycle with real poller threads ---------------------------------

    @Test
    void startSpinsUpAPollerThatPollsAndStopShutsItDown() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        consumer.start();

        // A worker thread should be long-polling the queue.
        await().atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> verify(sqs, atLeastOnce()).receiveMessage(any(ReceiveMessageRequest.class)));

        assertThatCode(consumer::stop).doesNotThrowAnyException();
    }

    @Test
    void stopIsANoOpWhenTheConsumerWasNeverStarted() {
        assertThatCode(consumer::stop).doesNotThrowAnyException();
    }
}
