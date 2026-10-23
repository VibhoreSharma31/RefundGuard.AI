package com.refundguard.repository;

import com.refundguard.model.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {
    Optional<RiskAssessment> findFirstByRefundIdOrderByAssessedAtDescIdDesc(Long refundId);
    List<RiskAssessment> findTop10ByRefundIdOrderByAssessedAtDescIdDesc(Long refundId);
    List<RiskAssessment> findByRefundIdIn(Collection<Long> refundIds);
}
