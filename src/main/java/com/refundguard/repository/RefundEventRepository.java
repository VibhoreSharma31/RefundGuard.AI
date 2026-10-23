package com.refundguard.repository;

import com.refundguard.model.RefundEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefundEventRepository extends JpaRepository<RefundEvent, Long> {
    List<RefundEvent> findByRefundIdOrderByEventTimeAsc(Long refundId);
    Optional<RefundEvent> findBySourceAndExternalEventId(String source, String externalEventId);
    long countByRefundIdAndEventTypeIgnoreCase(Long refundId, String eventType);
    boolean existsBySourceAndExternalEventId(String source, String externalEventId);
}
