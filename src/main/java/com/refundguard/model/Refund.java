package com.refundguard.model;

import com.refundguard.sla.SlaCalculator;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "rg_refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "provider_refund_id", unique = true)
    private String providerRefundId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "derived_status", nullable = false)
    private RefundStatus derivedStatus;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "bank_utr")
    private String bankUtr;

    @Column(name = "requested_at")
    private LocalDateTime requestedAt;

    @Column(name = "last_event_at")
    private LocalDateTime lastEventAt;

    @Column(name = "settled_amount")
    private BigDecimal settledAmount;

    // ── SLA / RBI TAT tracking ──
    // Policy is snapshotted when the refund is created so later policy edits
    // never silently change an existing refund's deadline.

    @Column(name = "sla_tat_days")
    private Integer slaTatDays;

    @Column(name = "sla_due_at")
    private LocalDateTime slaDueAt;

    @Column(name = "compensation_per_day")
    private BigDecimal compensationPerDay;

    /** Date the bank actually credited the customer (from the settlement file). */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** Last gateway event seen (refund.created / refund.processed / refund.failed); tells where a late refund is stuck. */
    @Column(name = "gateway_stage")
    private String gatewayStage;

    public Refund() {}

    // ── Computed SLA view (serialised to JSON, not stored) ──

    public String getSlaState() {
        if (derivedStatus == RefundStatus.RESOLVED && settledAt == null) return "CLOSED";
        return SlaCalculator.state(slaDueAt, settledAt, LocalDateTime.now());
    }

    public String getSlaLabel() {
        if (derivedStatus == RefundStatus.RESOLVED && settledAt == null) return "Closed manually";
        return SlaCalculator.label(slaDueAt, settledAt, LocalDateTime.now());
    }

    public long getDaysOverdue() {
        return SlaCalculator.daysOverdue(slaDueAt, settledAt, LocalDateTime.now());
    }

    public BigDecimal getCompensationDue() {
        if (derivedStatus == RefundStatus.RESOLVED && settledAt == null) return BigDecimal.ZERO;
        return SlaCalculator.compensation(getDaysOverdue(), compensationPerDay);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public void setProviderPaymentId(String providerPaymentId) { this.providerPaymentId = providerPaymentId; }
    public String getProviderRefundId() { return providerRefundId; }
    public void setProviderRefundId(String providerRefundId) { this.providerRefundId = providerRefundId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public RefundStatus getDerivedStatus() { return derivedStatus; }
    public void setDerivedStatus(RefundStatus derivedStatus) { this.derivedStatus = derivedStatus; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getBankUtr() { return bankUtr; }
    public void setBankUtr(String bankUtr) { this.bankUtr = bankUtr; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }
    public LocalDateTime getLastEventAt() { return lastEventAt; }
    public void setLastEventAt(LocalDateTime lastEventAt) { this.lastEventAt = lastEventAt; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public void setSettledAmount(BigDecimal settledAmount) { this.settledAmount = settledAmount; }
    public Integer getSlaTatDays() { return slaTatDays; }
    public void setSlaTatDays(Integer slaTatDays) { this.slaTatDays = slaTatDays; }
    public LocalDateTime getSlaDueAt() { return slaDueAt; }
    public void setSlaDueAt(LocalDateTime slaDueAt) { this.slaDueAt = slaDueAt; }
    public BigDecimal getCompensationPerDay() { return compensationPerDay; }
    public void setCompensationPerDay(BigDecimal compensationPerDay) { this.compensationPerDay = compensationPerDay; }
    public LocalDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(LocalDateTime settledAt) { this.settledAt = settledAt; }
    public String getGatewayStage() { return gatewayStage; }
    public void setGatewayStage(String gatewayStage) { this.gatewayStage = gatewayStage; }
}
