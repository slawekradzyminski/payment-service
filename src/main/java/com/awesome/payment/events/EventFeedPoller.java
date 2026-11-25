package com.awesome.payment.events;

import com.awesome.payment.client.BackendClient;
import com.awesome.payment.client.BackendException;
import com.awesome.payment.client.EventFeedPage;
import com.awesome.payment.client.FeedEvent;
import com.awesome.payment.correlation.Correlation;
import com.awesome.payment.entity.EventCursorEntity;
import com.awesome.payment.entity.ProcessedEventEntity;
import com.awesome.payment.repository.EventCursorRepository;
import com.awesome.payment.repository.ProcessedEventRepository;
import com.awesome.payment.tracing.Spans;
import io.micrometer.tracing.Span;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pulls the backend's domain event feed ({@code GET /internal/v1/events}) from the stored cursor,
 * asking only for the types a {@link FeedEventHandler} consumes. Each event is handled in its own
 * transaction that also records it in {@code processed_events} and advances the cursor, so a crash
 * never loses or repeats an event. An event id seen before is skipped. Only an event whose content
 * makes it fail ({@link #isPoisonEvent}: a malformed payload, or a final backend rejection) is logged
 * with its id and skipped, so one bad event never blocks the feed. Every other failure (the backend
 * unavailable, any database or transaction failure such as a dropped connection, or an unexpected
 * error) stops the poll without moving the cursor; the next tick retries, so no refund is lost. Scheduled every {@code payment.events.poll-interval},
 * switched off with {@code payment.events.enabled}. While an event is handled, the MDC holds the
 * event's {@code requestId} and {@code traceId} (when valid), so the card refund of a cancellation
 * logs, and is stored, with the trace of the backend request that cancelled the order. With
 * tracing on, each event is handled in a {@code CONSUMER} span {@code process <type>} whose parent
 * is the event's {@code traceId}/{@code spanId}, so the cancellation and its refund are one trace.
 *
 * <p>With {@code payment.events.transport=broker} the poller does not run: {@link EventBrokerListener}
 * hands every event to {@link #processDelivered}, which deduplicates by event id only and leaves the
 * cursor where the feed left it, so switching back to the feed resumes from there.
 */
@Slf4j
@Component
public class EventFeedPoller {

    public static final String CONSUMER = "payment-service";
    static final int PAGE_SIZE = 100;
    /** Upper bound of pages read in one poll, so a long backlog does not monopolise the scheduler. */
    static final int MAX_PAGES_PER_POLL = 20;

    private final BackendClient backendClient;
    private final EventCursorRepository cursors;
    private final ProcessedEventRepository processedEvents;
    private final Map<String, FeedEventHandler> handlers;
    private final List<String> types;
    private final TransactionTemplate transaction;
    private final Clock paymentClock;
    private final Spans spans;
    private final boolean enabled;

    public EventFeedPoller(BackendClient backendClient, EventCursorRepository cursors,
                           ProcessedEventRepository processedEvents, List<FeedEventHandler> handlers,
                           PlatformTransactionManager transactionManager, Clock paymentClock, Spans spans,
                           @Value("${payment.events.enabled:true}") boolean enabled,
                           @Value("${payment.events.transport:feed}") String transport) {
        this.backendClient = backendClient;
        this.cursors = cursors;
        this.processedEvents = processedEvents;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(FeedEventHandler::type,
                Function.identity()));
        this.types = this.handlers.keySet().stream().sorted().toList();
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.paymentClock = paymentClock;
        this.spans = spans;
        // With the broker transport the listener receives the events; the poller stays off.
        this.enabled = enabled && EventTransport.from(transport) == EventTransport.FEED;
    }

    @Scheduled(fixedDelayString = "${payment.events.poll-interval:PT5S}",
            initialDelayString = "${payment.events.poll-interval:PT5S}")
    public void scheduledPoll() {
        if (enabled) {
            poll();
        }
    }

    /** Reads and handles everything available (up to {@link #MAX_PAGES_PER_POLL} pages); returns how many events were handled. */
    public synchronized int poll() {
        int handled = 0;
        for (int page = 0; page < MAX_PAGES_PER_POLL; page++) {
            long after = cursor();
            EventFeedPage feed;
            try {
                feed = backendClient.events(after, PAGE_SIZE, types);
            } catch (BackendException e) {
                log.warn("Event feed unavailable after sequence {} ({}); retrying on the next poll", after,
                        e.getMessage());
                return handled;
            }
            for (FeedEvent event : feed.events()) {
                if (!process(event)) {
                    return handled;
                }
                handled++;
            }
            advanceTo(feed.nextCursor());
            if (!feed.hasMore()) {
                break;
            }
        }
        return handled;
    }

    /** The last handled sequence (0 before the first event). */
    public long cursor() {
        Long sequence = transaction.execute(status -> cursors.findById(CONSUMER)
                .map(EventCursorEntity::getLastSequence).orElse(0L));
        return sequence == null ? 0L : sequence;
    }

    /**
     * Handles one event delivered by the broker, in its own transaction: an event id already in
     * {@code processed_events} is skipped, anything else goes to its handler and is recorded. Unlike the
     * poller nothing is skipped on failure and the cursor is not used (broker deliveries are not in
     * sequence order once one is redelivered): every exception propagates, so the broker redelivers the
     * message and finally dead-letters it.
     *
     * @return false when the event was a duplicate
     */
    public boolean processDelivered(FeedEvent event) {
        String traceId = Correlation.validTraceId(event.traceId());
        try (Spans.Scope span = spans.child("process " + event.type(), Span.Kind.CONSUMER,
                Correlation.validRequestId(event.requestId()), traceId,
                traceId == null ? null : Correlation.validSpanId(event.spanId()))) {
            span.tag("event.id", event.id()).tag("event.type", event.type()).tag("event.sequence", event.sequence());
            try {
                Boolean handled = transaction.execute(status -> {
                    if (processedEvents.existsById(event.id())) {
                        log.info("Event {} ({} #{}) was already processed; duplicate delivery acknowledged",
                                event.id(), event.type(), event.sequence());
                        return false;
                    }
                    FeedEventHandler handler = handlers.get(event.type());
                    if (handler == null) {
                        log.warn("Event {} has unhandled type {}; acknowledged without effect", event.id(), event.type());
                        return false;
                    }
                    handler.handle(event);
                    markProcessed(event);
                    return true;
                });
                if (Boolean.TRUE.equals(handled)) {
                    log.info("Handled event {} ({} #{}) from the broker", event.id(), event.type(), event.sequence());
                }
                return Boolean.TRUE.equals(handled);
            } catch (RuntimeException e) {
                span.error(e);
                log.warn("Event {} ({} #{}) failed; the broker will redeliver it: {}", event.id(), event.type(),
                        event.sequence(), e.getMessage());
                throw e;
            }
        }
    }

    /** Handles one event; false when it must be retried later (any failure but a poison event). */
    private boolean process(FeedEvent event) {
        String traceId = Correlation.validTraceId(event.traceId());
        try (Spans.Scope span = spans.child("process " + event.type(), Span.Kind.CONSUMER,
                Correlation.validRequestId(event.requestId()), traceId,
                traceId == null ? null : Correlation.validSpanId(event.spanId()))) {
            span.tag("event.id", event.id()).tag("event.type", event.type()).tag("event.sequence", event.sequence());
            return handle(event, span);
        }
    }

    private boolean handle(FeedEvent event, Spans.Scope span) {
        try {
            transaction.executeWithoutResult(status -> {
                EventCursorEntity cursor = lockedCursor();
                if (event.sequence() <= cursor.getLastSequence() || processedEvents.existsById(event.id())) {
                    log.debug("Event {} (sequence {}) was already processed", event.id(), event.sequence());
                } else {
                    FeedEventHandler handler = handlers.get(event.type());
                    if (handler == null) {
                        log.warn("Event {} has unrequested type {}; ignored", event.id(), event.type());
                    } else {
                        handler.handle(event);
                    }
                    markProcessed(event);
                }
                advance(cursor, event.sequence());
            });
            return true;
        } catch (RuntimeException e) {
            if (isPoisonEvent(e)) {
                skip(event, e, span);
                return true;
            }
            if (e instanceof BackendException backend && backend.isTransient()) {
                log.warn("Event {} (sequence {}) needs the backend, which is unavailable; retrying on the next poll",
                        event.id(), event.sequence());
            } else if (isInfrastructureFailure(e)) {
                log.warn("Event {} (sequence {}) hit a database failure; retrying on the next poll: {}",
                        event.id(), event.sequence(), e.getMessage());
            } else {
                span.error(e);
                log.error("Event {} (sequence {}, type {}) failed unexpectedly; the cursor stays and the next poll "
                        + "retries it", event.id(), event.sequence(), event.type(), e);
            }
            return false;
        }
    }

    /**
     * A failure caused by the event's content, which no retry can fix: a malformed payload
     * ({@link IllegalArgumentException}, including number and UUID parse errors), unreadable JSON
     * ({@link JacksonException}) or a final backend rejection of what the event refers to. A database
     * or transaction failure anywhere in the cause chain is never poison, whatever wraps it.
     */
    static boolean isPoisonEvent(RuntimeException e) {
        if (isInfrastructureFailure(e)) {
            return false;
        }
        if (e instanceof BackendException backend) {
            return !backend.isTransient();
        }
        return e instanceof IllegalArgumentException || e instanceof JacksonException;
    }

    /**
     * A lost or refused database connection, a failed commit or any other data-access or transaction
     * infrastructure error ({@link DataAccessException}, {@link TransactionException},
     * {@link PersistenceException}, {@link SQLException}) anywhere in the cause chain.
     */
    static boolean isInfrastructureFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessException || cause instanceof TransactionException
                    || cause instanceof PersistenceException || cause instanceof SQLException) {
                return true;
            }
        }
        return false;
    }

    /** Records a failed event as processed and moves past it; the failure is only logged. */
    private void skip(FeedEvent event, RuntimeException failure, Spans.Scope span) {
        span.error(failure);
        log.error("Event {} (sequence {}, type {}) failed and is skipped", event.id(), event.sequence(), event.type(),
                failure);
        transaction.executeWithoutResult(status -> {
            EventCursorEntity cursor = lockedCursor();
            if (!processedEvents.existsById(event.id())) {
                markProcessed(event);
            }
            advance(cursor, event.sequence());
        });
    }

    /** Moves the cursor past events the backend filtered out of the page. */
    private void advanceTo(long nextCursor) {
        transaction.executeWithoutResult(status -> advance(lockedCursor(), nextCursor));
    }

    private EventCursorEntity lockedCursor() {
        return cursors.findForUpdate(CONSUMER).orElseGet(() -> cursors.saveAndFlush(
                new EventCursorEntity(CONSUMER, 0L, LocalDateTime.now(paymentClock))));
    }

    private void markProcessed(FeedEvent event) {
        processedEvents.save(new ProcessedEventEntity(event.id(), LocalDateTime.now(paymentClock)));
    }

    private void advance(EventCursorEntity cursor, long sequence) {
        if (sequence > cursor.getLastSequence()) {
            cursor.setLastSequence(sequence);
            cursor.setUpdatedAt(LocalDateTime.now(paymentClock));
            cursors.save(cursor);
        }
    }
}
