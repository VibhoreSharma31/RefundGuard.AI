package com.refundguard.repository;

import com.refundguard.model.RiskFactor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface RiskFactorRepository extends JpaRepository<RiskFactor, Long> {

    /** Factors of several assessments in one query (avoids N+1 in lists). */
    @Query("select f from RiskFactor f where f.assessment.id in :ids order by f.assessment.id, f.ordinal")
    List<RiskFactor> findByAssessmentIds(@Param("ids") Collection<Long> assessmentIds);

    @Query("select count(f) from RiskFactor f where f.assessment.id = :id")
    long countByAssessmentId(@Param("id") Long assessmentId);
}
