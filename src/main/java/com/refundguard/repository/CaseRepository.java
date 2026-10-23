package com.refundguard.repository;

import com.refundguard.model.Case;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CaseRepository extends JpaRepository<Case, Long> {
    List<Case> findByStatus(String status);
    List<Case> findByRefundId(Long refundId);
    List<Case> findByRefundIdAndStatus(Long refundId, String status);
    Optional<Case> findFirstByRefundIdAndIssueTypeAndStatus(Long refundId, String issueType, String status);
    long countByRefundIdAndStatusAndIssueTypeIn(Long refundId, String status, Collection<String> issueTypes);
    boolean existsBySettlementRowIdAndIssueType(Long settlementRowId, String issueType);
    List<Case> findBySettlementRowIdAndStatus(Long settlementRowId, String status);
    List<Case> findByRefundIdIn(Collection<Long> refundIds);
}
