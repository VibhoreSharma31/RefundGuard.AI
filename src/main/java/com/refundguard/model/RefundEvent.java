package com.refundguard.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rg_refund_events", 
       uniqueConstraints = @UniqueConstraint(name = "uk_rg_source_event", columnNames = {"source", "external_event_id"}))
public class RefundEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id")
    private Long refundId;

    @Column(nullable = false)
    private String source;

    @Column(name = "external_event_id", nullable = false)
    private String externalEventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Lob
    @Column(name = "raw_payload")
    private String rawPayload;

    @Column(name = "event_time")
    private LocalDateTime eventTime;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    public RefundEvent() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String externalEventId) { this.externalEventId = externalEventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}
