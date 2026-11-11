package com.awesome.payment.events;

import com.awesome.payment.client.BackendClient;
import com.awesome.payment.client.BackendException;
import com.awesome.payment.client.EventFeedPage;
import com.awesome.payment.client.FeedEvent;
import com.awesome.payment.entity.EventCursorEntity;
import com.awesome.payment.entity.ProcessedEventEntity;
import com.awesome.payment.repository.EventCursorRepository;
import com.awesome.payment.repository.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
 * never loses or repeats an event. An event id seen before is skipped. The backend being
 * unavailable (or a transient database failure) stops the poll without moving the cursor; the next
 * tick retries. Any other handler failure is logged with the event id and the event is skipped,
 * so one bad event never blocks the feed. Scheduled every {@code payment.events.poll-interval},
 * switched off with {@code payment.events.enabled}.
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
    private final boolean enabled;

    public EventFeedPoller(BackendClient backendClient, EventCursorRepository cursors,
                           ProcessedEventRepository processedEvents, List<FeedEventHandler> handlers,
                           PlatformTransactionManager transactionManager, Clock paymentClock,
                           @Value("${payment.events.enabled:true}") boolean enabled) {
        this.backendClient = backendClient;
        this.cursors = cursors;
        this.processedEvents = processedEvents;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(FeedEventHandler::type,
                Function.identity()));
        this.types = this.handlers.keySet().stream().sorted().toList();
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.paymentClock = paymentClock;
        this.enabled = enabled;
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

    /** Handles one event; false when it must be retried later (transient failure). */
    private boolean process(FeedEvent event) {
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
        } catch (TransientDataAccessException e) {
            log.warn("Event {} (sequence {}) hit a transient database failure; retrying on the next poll: {}",
                    event.id(), event.sequence(), e.getMessage());
            return false;
        } catch (BackendException e) {
            if (e.isTransient()) {
                log.warn("Event {} (sequence {}) needs the backend, which is unavailable; retrying on the next poll",
                        event.id(), event.sequence());
                return false;
            }
            skip(event, e);
            return true;
        } catch (RuntimeException e) {
            skip(event, e);
            return true;
        }
    }

    /** Records a failed event as processed and moves past it; the failure is only logged. */
    private void skip(FeedEvent event, RuntimeException failure) {
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
