package com.example.collections;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;

interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByUsernameIgnoreCase(String username);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where lower(a.username)=lower(:username)")
    Optional<Account> findForLogin(@Param("username") String username);
}

interface EntryRepository extends JpaRepository<CollectionEntry, Long>, JpaSpecificationExecutor<CollectionEntry> {
    boolean existsByTrackingNumberIgnoreCase(String value);
    boolean existsByPrnIgnoreCase(String value);
    Optional<CollectionEntry> findFirstByTrackingNumberIgnoreCase(String value);
    Optional<CollectionEntry> findFirstByPrnIgnoreCase(String value);
    Page<CollectionEntry> findByCreatedById(Long id, Pageable pageable);
    @Query("select count(e), coalesce(sum(e.amount),0) from CollectionEntry e where e.collectedAt >= :start and e.collectedAt < :end and (:ownerId is null or e.createdBy.id = :ownerId)")
    Object[] totals(@Param("start") Instant start, @Param("end") Instant end, @Param("ownerId") Long ownerId);
}

interface AuditRepository extends JpaRepository<AuditEvent, Long> {}
