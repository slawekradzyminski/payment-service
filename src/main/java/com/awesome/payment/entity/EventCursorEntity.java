package com.awesome.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** How far a consumer has read the backend event feed: the last handled sequence. */
@Entity
@Table(name = "event_cursors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EventCursorEntity {

    @Id
    @Column(length = 64)
    private String consumer;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
