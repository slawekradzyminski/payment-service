package com.awesome.payment.repository;

import com.awesome.payment.entity.IdempotencyKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, Long> {

    Optional<IdempotencyKeyEntity> findByUsernameAndIdempotencyKey(String username, String idempotencyKey);
}
