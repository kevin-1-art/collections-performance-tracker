package com.example.collections;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.Locale;
import java.util.Map;

@Service
class EntryService {
    static final ZoneId ZONE=ZoneId.of("Africa/Kampala");
    private final EntryRepository entries;
    private final AccountRepository accounts;
    private final AuditService audit;
    EntryService(EntryRepository entries, AccountRepository accounts, AuditService audit) {
        this.entries=entries; this.accounts=accounts; this.audit=audit;
    }
    @Transactional public EntryView create(EntryRequest r, String username, Long actorId) {
        var existing=entries.findFirstByTrackingNumberIgnoreCase(r.trackingNumber().trim())
                .or(()->entries.findFirstByPrnIgnoreCase(r.prn().trim())).orElse(null);
        if(existing!=null) duplicate(existing);
        CollectionEntry e=new CollectionEntry(); e.clientName=r.clientName().trim(); e.trackingNumber=r.trackingNumber().trim();
        e.prn=r.prn().trim(); e.amount=r.amount(); e.collectedAt=r.collectedAt(); e.receiptNumber=blankToNull(r.receiptNumber());
        e.notes=blankToNull(r.notes()); e.createdBy=accounts.findByUsernameIgnoreCase(username).orElseThrow();
        try { e=entries.saveAndFlush(e); }
        catch(DataIntegrityViolationException ex) {
            throw new DuplicateEntryException(r.trackingNumber().trim(),r.prn().trim());
        }

        audit.record(actorId,"CREATE","ENTRY",e.id.toString(),"Added collection entry");
        return EntryView.of(e);
    }
    private void duplicate(CollectionEntry e) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,"Duplicate tracking number or PRN; existing record #"+e.id+
                " (tracking="+e.trackingNumber+", PRN="+e.prn+")");
    }

    private String blankToNull(String s){ return s==null||s.isBlank()?null:s.trim(); }
    @Transactional(readOnly=true) public Page<EntryView> list(String username, boolean admin, int page, int size,
            String q, String tracking, String prn, Instant from, Instant to) {
        if(page<0||size<1||size>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"page must be >=0 and size 1..100");
        Long ownId=accounts.findByUsernameIgnoreCase(username).orElseThrow().id;
        Specification<CollectionEntry> spec=(root,query,cb)->cb.conjunction();
        if(!admin) spec=spec.and((root,query,cb)->cb.equal(root.get("createdBy").get("id"),ownId));
        if(q!=null&&!q.isBlank()) { String pattern="%"+q.trim().toLowerCase(Locale.ROOT)+"%";
            spec=spec.and((root,query,cb)->cb.or(cb.like(cb.lower(root.get("clientName")),pattern),
                    cb.like(cb.lower(root.get("trackingNumber")),pattern),cb.like(cb.lower(root.get("prn")),pattern))); }
        if(tracking!=null&&!tracking.isBlank()) spec=spec.and((root,query,cb)->cb.like(cb.lower(root.get("trackingNumber")),"%"+tracking.toLowerCase(Locale.ROOT)+"%"));
        if(prn!=null&&!prn.isBlank()) spec=spec.and((root,query,cb)->cb.like(cb.lower(root.get("prn")),"%"+prn.toLowerCase(Locale.ROOT)+"%"));
        if(from!=null) spec=spec.and((root,query,cb)->cb.greaterThanOrEqualTo(root.get("collectedAt"),from));
        if(to!=null) spec=spec.and((root,query,cb)->cb.lessThan(root.get("collectedAt"),to));
        return entries.findAll(spec,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"collectedAt"))).map(EntryView::of);
    }
    @Transactional public void delete(long id, boolean admin, String username, Long actorId) {
        CollectionEntry e=entries.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Entry not found"));
        if(!admin&&!e.createdBy.username.equalsIgnoreCase(username)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You can only delete your own entries");
        audit.record(actorId,"DELETE","ENTRY",Long.toString(id),"Deleted "+e.trackingNumber+" / "+e.prn+" / "+e.amount);
        entries.delete(e);
    }
    public Summary summary(String username, boolean admin, String period, LocalDate from, LocalDate to) {
        PeriodWindow window;
        try { window=PeriodWindow.forPeriod(period,from,to,ZonedDateTime.now(ZONE).toLocalDate()); }
        catch(IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,ex.getMessage()); }
        Long ownerId=admin?null:accounts.findByUsernameIgnoreCase(username).orElseThrow().id;
        Object[] totals=entries.totals(window.start(),window.end(),ownerId);
        long count=((Number)totals[0]).longValue();
        java.math.BigDecimal amount=new java.math.BigDecimal(totals[1].toString());
        return new Summary(count,amount,window.start().toString(),window.end().toString(),window.label());
    }
}

class DuplicateEntryException extends RuntimeException {
    final String trackingNumber;
    final String prn;
    DuplicateEntryException(String trackingNumber,String prn) {
        super("Duplicate tracking number or PRN");
        this.trackingNumber=trackingNumber; this.prn=prn;
    }
}

@org.springframework.web.bind.annotation.RestControllerAdvice
class DuplicateEntryAdvice {
    private final EntryRepository entries;
    DuplicateEntryAdvice(EntryRepository entries) { this.entries=entries; }
    @org.springframework.web.bind.annotation.ExceptionHandler(DuplicateEntryException.class)
    org.springframework.http.ResponseEntity<Map<String,String>> handle(DuplicateEntryException ex) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("detail",details(ex,entries)));
    }
    static String details(DuplicateEntryException ex,EntryRepository entries) {
        CollectionEntry found=entries.findFirstByTrackingNumberIgnoreCase(ex.trackingNumber)
                .or(()->entries.findFirstByPrnIgnoreCase(ex.prn)).orElse(null);
        if(found==null) return "Tracking number or PRN is already in use";
        return "Duplicate tracking number or PRN; existing record #"+found.id+
                " (tracking="+found.trackingNumber+", PRN="+found.prn+")";
    }
}

record PeriodWindow(String label,Instant start,Instant end) {
    static PeriodWindow forPeriod(String period,LocalDate from,LocalDate to,LocalDate today) {
        ZoneId zone=EntryService.ZONE;
        if("week".equalsIgnoreCase(period)) {
            int sinceFriday=(today.getDayOfWeek().getValue()-DayOfWeek.FRIDAY.getValue()+7)%7;
            LocalDate friday=today.minusDays(sinceFriday);
            return new PeriodWindow("Friday–Thursday",friday.atStartOfDay(zone).toInstant(),
                    friday.plusDays(7).atStartOfDay(zone).toInstant());
        }
        if("day".equalsIgnoreCase(period))
            return new PeriodWindow("Today",today.atStartOfDay(zone).toInstant(),today.plusDays(1).atStartOfDay(zone).toInstant());
        if("custom".equalsIgnoreCase(period)&&from!=null&&to!=null&&!to.isBefore(from))
            return new PeriodWindow("Custom",from.atStartOfDay(zone).toInstant(),to.plusDays(1).atStartOfDay(zone).toInstant());
        throw new IllegalArgumentException("period must be day, week, or custom with valid from and to dates");
    }
}
