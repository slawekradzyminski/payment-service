package com.awesome.payment.events;

import com.awesome.payment.client.BackendClient;
import com.awesome.payment.client.BackendErrorMapper;
import com.awesome.payment.client.BackendException;
import com.awesome.payment.client.EventFeedPage;
import com.awesome.payment.client.FeedEvent;
import com.awesome.payment.entity.EventCursorEntity;
import com.awesome.payment.entity.ProcessedEventEntity;
import com.awesome.payment.repository.EventCursorRepository;
import com.awesome.payment.repository.ProcessedEventRepository;
import com.awesome.payment.tracing.Spans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Failure handling the flow tests cannot provoke: database and transaction failures, poison events, backend 4xx, unknown types. */
class EventFeedPollerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);

    private final BackendClient backend = mock(BackendClient.class);
    private final EventCursorRepository cursors = mock(EventCursorRepository.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final FeedEventHandler handler = mock(FeedEventHandler.class);
    private final List<UUID> processedIds = new ArrayList<>();
    private EventCursorEntity cursor;

    @BeforeEach
    void setUp() {
        cursor = new EventCursorEntity(EventFeedPoller.CONSUMER, 0L, null);
        when(handler.type()).thenReturn("card-refund.requested");
        when(cursors.findById(EventFeedPoller.CONSUMER)).thenAnswer(i -> Optional.of(cursor));
        when(cursors.findForUpdate(EventFeedPoller.CONSUMER)).thenAnswer(i -> Optional.of(cursor));
        when(processed.existsById(any())).thenAnswer(i -> processedIds.contains(i.<UUID>getArgument(0)));
        when(processed.save(any())).thenAnswer(i -> {
            processedIds.add(i.<ProcessedEventEntity>getArgument(0).getEventId());
            return i.getArgument(0);
        });
    }

    @Test
    void aTransientDatabaseFailureStopsThePollWithoutMovingTheCursor() {
        FeedEvent first = event(1, "card-refund.requested");
        FeedEvent second = event(2, "card-refund.requested");
        feed(new EventFeedPage(List.of(first, second), 2, false));
        doThrow(new QueryTimeoutException("lock timeout")).when(handler).handle(first);

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
        assertThat(processedIds).isEmpty();
        verify(handler, never()).handle(second);
    }

    @Test
    void droppedConnectionKeepsCursor() {
        FeedEvent refund = event(1, "card-refund.requested");
        FeedEvent next = event(2, "card-refund.requested");
        feed(new EventFeedPage(List.of(refund, next), 2, false));
        doThrow(new DataAccessResourceFailureException("Connection refused")).when(handler).handle(refund);
        EventFeedPoller poller = poller(true);

        assertThat(poller.poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
        assertThat(processedIds).isEmpty();
        verify(processed, never()).save(any());
        verify(handler, never()).handle(next);

        // The connection is back: the same event is handled on the next poll.
        doNothing().when(handler).handle(refund);
        assertThat(poller.poll()).isEqualTo(2);
        assertThat(processedIds).containsExactly(refund.id(), next.id());
        assertThat(cursor.getLastSequence()).isEqualTo(2);
    }

    static Stream<RuntimeException> infrastructureFailures() {
        return Stream.of(
                new DataAccessResourceFailureException("Connection refused"),
                new CannotGetJdbcConnectionException("pool exhausted", new SQLException("timeout")),
                new CannotCreateTransactionException("Could not open JPA EntityManager"),
                new JpaSystemException(new RuntimeException("connection closed")),
                new TransactionSystemException("Could not commit JPA transaction"),
                new DataIntegrityViolationException("duplicate key"),
                new PersistenceException("connection reset"),
                new IllegalStateException("wrapped", new SQLException("I/O error")),
                new IllegalArgumentException("wrapped", new DataAccessResourceFailureException("gone")),
                new IllegalStateException("not caused by the event"),
                new NullPointerException());
    }

    @ParameterizedTest
    @MethodSource("infrastructureFailures")
    void anyFailureNotCausedByTheEventKeepsTheCursor(RuntimeException failure) {
        FeedEvent refund = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(refund), 1, false));
        doThrow(failure).when(handler).handle(refund);

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
        assertThat(processedIds).isEmpty();
        assertThat(EventFeedPoller.isPoisonEvent(failure)).isFalse();
    }

    @Test
    void aConnectionLostAtCommitKeepsTheCursor() {
        FeedEvent refund = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(refund), 1, false));
        InlineTransactionManager transactions = new InlineTransactionManager();
        transactions.failCommits = true;

        assertThat(poller(transactions).poll()).isZero();

        verify(handler).handle(refund);
        // The cursor read and the failed event commit; no skip transaction recorded the event.
        assertThat(transactions.commits).isEqualTo(2);
    }

    static Stream<RuntimeException> poisonEvents() {
        return Stream.of(
                new IllegalArgumentException("card-refund.requested event has no requestId"),
                new NumberFormatException("For input string: \"x\""),
                jsonFailure(),
                new BackendException("Unprocessable", HttpStatus.UNPROCESSABLE_CONTENT, 422, null));
    }

    @ParameterizedTest
    @MethodSource("poisonEvents")
    void aPoisonEventIsStillSkipped(RuntimeException failure) {
        FeedEvent poison = event(1, "card-refund.requested");
        FeedEvent next = event(2, "card-refund.requested");
        feed(new EventFeedPage(List.of(poison, next), 2, false));
        doThrow(failure).when(handler).handle(poison);

        assertThat(poller(true).poll()).isEqualTo(2);

        assertThat(processedIds).containsExactly(poison.id(), next.id());
        assertThat(cursor.getLastSequence()).isEqualTo(2);
        assertThat(EventFeedPoller.isPoisonEvent(failure)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 401, 403, 408, 429, 500, 503})
    void aRetryableBackendFailureIsNotPoison(int status) {
        BackendException failure = status == 0 ? BackendErrorMapper.unavailable(null)
                : BackendErrorMapper.fromResponse(HttpStatusCode.valueOf(status), null);

        assertThat(EventFeedPoller.isPoisonEvent(failure)).isFalse();
    }

    @Test
    void aBrokerDeliveryHittingADroppedConnectionIsRolledBackForRedelivery() {
        FeedEvent refund = event(1, "card-refund.requested");
        doThrow(new DataAccessResourceFailureException("Connection refused")).when(handler).handle(refund);
        EventFeedPoller poller = poller(true);

        assertThatThrownBy(() -> poller.processDelivered(refund)).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(processedIds).isEmpty();
        assertThat(cursor.getLastSequence()).isZero();
    }

    private static RuntimeException jsonFailure() {
        try {
            JsonMapper.builder().build().readTree("{not json");
        } catch (JacksonException e) {
            return e;
        }
        throw new AssertionError("expected a parse failure");
    }

    @Test
    void eventsBeforeATransientFailureKeepTheirCursorPosition() {
        FeedEvent first = event(1, "card-refund.requested");
        FeedEvent skipped = event(2, "card-refund.requested");
        FeedEvent stuck = event(3, "card-refund.requested");
        feed(new EventFeedPage(List.of(first, skipped, stuck), 3, false));
        doThrow(new IllegalArgumentException("malformed")).when(handler).handle(skipped);
        doThrow(new QueryTimeoutException("lock timeout")).when(handler).handle(stuck);

        assertThat(poller(true).poll()).isEqualTo(2);

        assertThat(cursor.getLastSequence()).isEqualTo(2);
        assertThat(cursor.getUpdatedAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThat(processedIds).containsExactly(first.id(), skipped.id());
    }

    @Test
    void aHandledEventMovesTheCursorEvenWhenTheNextOneIsStuck() {
        FeedEvent first = event(1, "card-refund.requested");
        FeedEvent stuck = event(2, "card-refund.requested");
        feed(new EventFeedPage(List.of(first, stuck), 2, false));
        doThrow(new QueryTimeoutException("lock timeout")).when(handler).handle(stuck);

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }

    @Test
    void anOutageOnALaterPageKeepsTheCountOfHandledEvents() {
        FeedEvent first = event(1, "card-refund.requested");
        when(backend.events(anyLong(), anyInt(), anyList()))
                .thenReturn(new EventFeedPage(List.of(first), 1, true))
                .thenThrow(BackendErrorMapper.unavailable(null));

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(cursor.getLastSequence()).isEqualTo(1);
    }

    @Test
    void anEventAtTheCursorIsNotHandledAgain() {
        cursor.setLastSequence(5);
        FeedEvent same = event(5, "card-refund.requested");
        feed(new EventFeedPage(List.of(same), 5, false));

        poller(true).poll();

        verify(handler, never()).handle(any());
    }

    @Test
    void aTransientBackendFailureInsideAHandlerIsRetriedLater() {
        FeedEvent first = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(first), 1, false));
        doThrow(BackendErrorMapper.unavailable(null)).when(handler).handle(first);

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
    }

    @Test
    void aPermanentBackendRejectionInsideAHandlerSkipsTheEvent() {
        FeedEvent first = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(first), 1, false));
        doThrow(new BackendException("Order not found", HttpStatus.NOT_FOUND, 404, null)).when(handler).handle(first);

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(cursor.getLastSequence()).isEqualTo(1);
        assertThat(processedIds).containsExactly(first.id());
    }

    @Test
    void aFeedRejectionIsTreatedLikeAnOutage() {
        when(backend.events(anyLong(), anyInt(), anyList()))
                .thenThrow(new BackendException("Bad request", HttpStatus.BAD_REQUEST, 400, null));

        assertThat(poller(true).poll()).isZero();

        assertThat(cursor.getLastSequence()).isZero();
    }

    @Test
    void anUnrequestedTypeIsRecordedButNotHandled() {
        FeedEvent other = event(3, "order.created");
        feed(new EventFeedPage(List.of(other), 3, false));

        assertThat(poller(true).poll()).isEqualTo(1);

        verify(handler, never()).handle(any());
        assertThat(processedIds).containsExactly(other.id());
        assertThat(cursor.getLastSequence()).isEqualTo(3);
    }

    @Test
    void anEventAtOrBeforeTheCursorIsNotHandledAgain() {
        cursor.setLastSequence(5);
        FeedEvent old = event(4, "card-refund.requested");
        feed(new EventFeedPage(List.of(old), 5, false));

        assertThat(poller(true).poll()).isEqualTo(1);

        verify(handler, never()).handle(any());
        assertThat(cursor.getLastSequence()).isEqualTo(5);
    }

    @Test
    void aMissingCursorRowIsCreatedAtZero() {
        when(cursors.findById(EventFeedPoller.CONSUMER)).thenReturn(Optional.empty());
        when(cursors.findForUpdate(EventFeedPoller.CONSUMER)).thenReturn(Optional.empty());
        when(cursors.saveAndFlush(any())).thenAnswer(i -> {
            cursor = i.getArgument(0);
            return cursor;
        });
        FeedEvent first = event(1, "card-refund.requested");
        feed(new EventFeedPage(List.of(first), 1, false));

        assertThat(poller(true).poll()).isEqualTo(1);

        assertThat(cursor.getConsumer()).isEqualTo(EventFeedPoller.CONSUMER);
        assertThat(cursor.getLastSequence()).isEqualTo(1);
        assertThat(cursor.getUpdatedAt()).isNotNull();
    }

    @Test
    void aPollStopsAfterTheMaximumNumberOfPages() {
        when(backend.events(anyLong(), anyInt(), anyList())).thenAnswer(i -> {
            long after = i.getArgument(0);
            return new EventFeedPage(List.of(event(after + 1, "card-refund.requested")), after + 1, true);
        });

        assertThat(poller(true).poll()).isEqualTo(EventFeedPoller.MAX_PAGES_PER_POLL);

        assertThat(cursor.getLastSequence()).isEqualTo(EventFeedPoller.MAX_PAGES_PER_POLL);
    }

    @Test
    void theScheduledPollHonoursTheSwitch() {
        feed(new EventFeedPage(List.of(), 0, false));

        poller(false).scheduledPoll();
        verify(backend, never()).events(anyLong(), anyInt(), anyList());

        poller(true).scheduledPoll();
        verify(backend).events(0L, EventFeedPoller.PAGE_SIZE, List.of("card-refund.requested"));
    }

    private void feed(EventFeedPage page) {
        when(backend.events(anyLong(), anyInt(), anyList())).thenReturn(page);
    }

    private static FeedEvent event(long sequence, String type) {
        return new FeedEvent(sequence, UUID.randomUUID(), type, "2026-11-10T12:00:00Z", 1L, "client", null);
    }

    private EventFeedPoller poller(boolean enabled) {
        return new EventFeedPoller(backend, cursors, processed, List.of(handler), new InlineTransactionManager(), CLOCK,
                Spans.disabled(), enabled, "feed");
    }

    private EventFeedPoller poller(InlineTransactionManager transactions) {
        return new EventFeedPoller(backend, cursors, processed, List.of(handler), transactions, CLOCK,
                Spans.disabled(), true, "feed");
    }

    static class InlineTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;
        /** Every commit after the first (the cursor read) fails like a connection lost while committing. */
        boolean failCommits;
        int commits;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // runs inline
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommits && commits++ > 0) {
                throw new TransactionSystemException("Could not commit JPA transaction: connection reset");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // nothing to roll back
        }
    }
}
