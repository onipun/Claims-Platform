package com.chubb.claims.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {}
