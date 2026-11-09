package com.example.collections;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name="accounts")
class Account {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false, unique=true, length=80) String username;
    @Column(name="display_name", nullable=false, length=160) String displayName;
    @Column(name="password_hash", nullable=false, length=100) String passwordHash;
    @Column(nullable=false, length=20) String role;
    @Column(nullable=false) boolean enabled=true;
    @Column(name="must_change_password", nullable=false) boolean mustChangePassword;
    @Column(name="failed_attempts", nullable=false) int failedAttempts;
    @Column(name="locked_until") Instant lockedUntil;
    @Column(name="created_at", nullable=false) Instant createdAt=Instant.now();
}

@Entity @Table(name="collection_entries", uniqueConstraints={
    @UniqueConstraint(name="uq_entries_tracking_number", columnNames="tracking_number"),
    @UniqueConstraint(name="uq_entries_prn", columnNames="prn")})
class CollectionEntry {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="client_name", nullable=false, length=160) String clientName;
    @Column(name="tracking_number", nullable=false, length=80) String trackingNumber;
    @Column(nullable=false, length=80) String prn;
    @Column(nullable=false, precision=14, scale=2) BigDecimal amount;
    @Column(name="collected_at", nullable=false) Instant collectedAt;
    @Column(name="receipt_number", length=80) String receiptNumber;
    @Column(length=1000) String notes;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="created_by", nullable=false) Account createdBy;
    @Column(name="created_at", nullable=false) Instant createdAt=Instant.now();
}

@Entity @Table(name="audit_events")
class AuditEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="actor_id") Long actorId;
    @Column(nullable=false, length=40) String action;
    @Column(name="entity_type", nullable=false, length=40) String entityType;
    @Column(name="entity_id", length=100) String entityId;
    @Column(length=1000) String details;
    @Column(name="occurred_at", nullable=false) Instant occurredAt=Instant.now();
}
