package com.refundguard.repository;

import com.refundguard.model.SettlementRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface SettlementRowRepository extends JpaRepository<SettlementRow, Long> {
    List<SettlementRow> findByIsMatchedFalse();
    List<SettlementRow> findByFileId(String fileId);
    List<SettlementRow> findByRefundReferenceIn(Collection<String> refundReferences);
}
