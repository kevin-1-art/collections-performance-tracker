package com.example.collections;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
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
    @EntityGraph(attributePaths="createdBy")
    Optional<CollectionEntry> findFirstByTrackingNumberIgnoreCase(String value);
    @EntityGraph(attributePaths="createdBy")
    Optional<CollectionEntry> findFirstByPrnIgnoreCase(String value);
    Page<CollectionEntry> findByCreatedById(Long id, Pageable pageable);
    @Query("select count(e), coalesce(sum(e.amount),0) from CollectionEntry e where e.date between :start and :end and (:ownerId is null or e.createdBy.id = :ownerId) and (:officerId is null or e.createdBy.id = :officerId)")
    Object[] totals(@Param("start") LocalDate start, @Param("end") LocalDate end,
                    @Param("ownerId") Long ownerId, @Param("officerId") Long officerId);
    @Query("select e.date, count(e), coalesce(sum(e.amount),0) from CollectionEntry e where e.date between :start and :end and (:ownerId is null or e.createdBy.id = :ownerId) and (:officerId is null or e.createdBy.id = :officerId) group by e.date order by e.date")
    java.util.List<Object[]> dailyTotals(@Param("start") LocalDate start, @Param("end") LocalDate end,
                                         @Param("ownerId") Long ownerId, @Param("officerId") Long officerId);
    @Query("select coalesce(sum(e.amount),0) from CollectionEntry e where (:ownerId is null or e.createdBy.id = :ownerId) and (:officerId is null or e.createdBy.id = :officerId) and (:fromDate is null or e.date >= :fromDate) and (:toDate is null or e.date <= :toDate) and (:query is null or lower(e.trackingNumber) like lower(concat('%',:query,'%')) or lower(e.prn) like lower(concat('%',:query,'%')))")
    Long filteredTotal(@Param("ownerId") Long ownerId, @Param("officerId") Long officerId,
                       @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
                       @Param("query") String query);
}

interface AuditRepository extends JpaRepository<AuditEvent, Long> {}
