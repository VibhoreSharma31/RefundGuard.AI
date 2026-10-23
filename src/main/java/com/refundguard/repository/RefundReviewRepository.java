package com.refundguard.repository;

import com.refundguard.model.RefundReview;
import com.refundguard.review.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefundReviewRepository extends JpaRepository<RefundReview, Long> {
    Optional<RefundReview> findFirstByRefundIdAndStatus(Long refundId, ReviewStatus status);
    boolean existsByRefundId(Long refundId);
    long countByStatus(ReviewStatus status);
    List<RefundReview> findByStatusOrderByHeldAtAsc(ReviewStatus status);
    List<RefundReview> findTop100ByOrderByHeldAtDesc();
    List<RefundReview> findByRefundIdOrderByHeldAtDesc(Long refundId);

    // ── review-case workflow ──
    Optional<RefundReview> findFirstByRefundIdAndStatusIn(Long refundId, Collection<ReviewStatus> statuses);
    boolean existsByRefundIdAndStatusIn(Long refundId, Collection<ReviewStatus> statuses);
    long countByStatusIn(Collection<ReviewStatus> statuses);
    List<RefundReview> findByStatusInOrderByHeldAtAsc(Collection<ReviewStatus> statuses);

    /** Scalar lookup that does not load the entity, so the review can be read fresh after the refund row is locked. */
    @Query("select r.refundId from RefundReview r where r.id = :id")
    Optional<Long> findRefundIdById(@Param("id") Long id);
}
