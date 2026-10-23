package com.refundguard.repository;

import com.refundguard.model.Refund;
import com.refundguard.model.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.QueryHint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefundRepository extends JpaRepository<Refund, Long> {
    Optional<Refund> findByOrderId(String orderId);
    Optional<Refund> findByProviderRefundId(String providerRefundId);
    List<Refund> findByDerivedStatus(RefundStatus status);
    List<Refund> findBySettledAtIsNull();
    long countByOrderId(String orderId);
    List<Refund> findByOrderIdIn(Collection<String> orderIds);

    /** Row lock used by the approval workflow so two reviewers can never decide the same refund concurrently. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") Long id);
}
