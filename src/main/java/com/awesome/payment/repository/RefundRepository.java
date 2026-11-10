package com.awesome.payment.repository;

import com.awesome.payment.entity.RefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<RefundEntity, UUID> {

    Optional<RefundEntity> findByRequestId(UUID requestId);
}
