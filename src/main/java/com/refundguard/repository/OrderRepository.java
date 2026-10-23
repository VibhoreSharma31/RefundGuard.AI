package com.refundguard.repository;

import com.refundguard.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByExternalOrderId(String externalOrderId);
    List<Order> findByCustomerEmailIgnoreCase(String customerEmail);
}
