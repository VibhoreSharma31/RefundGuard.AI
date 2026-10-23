package com.refundguard.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "rg_settlement_rows")
public class SettlementRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_id")
    private String fileId;

    @Column(name = "refund_reference")
    private String refundReference;

    @Column(name = "utr_number")
    private String utrNumber;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    @Column(name = "is_matched")
    private Boolean isMatched = false;

    public SettlementRow() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getRefundReference() { return refundReference; }
    public void setRefundReference(String refundReference) { this.refundReference = refundReference; }
    public String getUtrNumber() { return utrNumber; }
    public void setUtrNumber(String utrNumber) { this.utrNumber = utrNumber; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDate getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDate settlementDate) { this.settlementDate = settlementDate; }
    public Boolean getIsMatched() { return isMatched; }
    public void setIsMatched(Boolean isMatched) { this.isMatched = isMatched; }
}
