package com.example.collections;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

@Service
class EntryService {
    static final ZoneId ZONE=ZoneId.of("Africa/Kampala");
    private final EntryRepository entries;
    private final AccountRepository accounts;
    private final AuditService audit;
    EntryService(EntryRepository entries, AccountRepository accounts, AuditService audit) {
        this.entries=entries; this.accounts=accounts; this.audit=audit;
    }

    @Transactional public EntryView create(EntryRequest request,String username,Long actorId) {
        validateDate(request.date());
        long amount=parseAmount(request.amount());
        String tracking=request.trackingNumber().trim().toUpperCase(Locale.ROOT);
        String prn=request.prn().trim();
        entries.findFirstByTrackingNumberIgnoreCase(tracking).ifPresent(existing->duplicate(existing,"tracking number"));
        entries.findFirstByPrnIgnoreCase(prn).ifPresent(existing->duplicate(existing,"PRN"));
        CollectionEntry entry=new CollectionEntry();
        entry.date=request.date(); entry.trackingNumber=tracking; entry.prn=prn;
        entry.amount=amount; entry.createdBy=accounts.findByUsernameIgnoreCase(username).orElseThrow();
        try {
            entry=entries.saveAndFlush(entry);
        } catch(DataIntegrityViolationException ex) {
            throw new DuplicateEntryException(tracking,prn);
        }
        audit.record(actorId,"CREATE","ENTRY",entry.id.toString(),"Added "+entry.trackingNumber+" / "+entry.prn+" / "+entry.amount);
        return EntryView.of(entry);
    }

    @Transactional public EntryView update(long id,EntryRequest request,boolean admin,String username,Long actorId) {
        validateDate(request.date());
        long amount=parseAmount(request.amount());
        CollectionEntry entry=entries.findById(id)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Entry not found"));
        if(!admin&&!entry.createdBy.username.equalsIgnoreCase(username))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You can only edit your own entries");
        String tracking=request.trackingNumber().trim().toUpperCase(Locale.ROOT);
        String prn=request.prn().trim();
        entries.findFirstByTrackingNumberIgnoreCase(tracking).filter(existing->!existing.id.equals(id))
                .ifPresent(existing->duplicate(existing,"tracking number"));
        entries.findFirstByPrnIgnoreCase(prn).filter(existing->!existing.id.equals(id))
                .ifPresent(existing->duplicate(existing,"PRN"));
        entry.date=request.date(); entry.trackingNumber=tracking; entry.prn=prn; entry.amount=amount;
        try {
            entry=entries.saveAndFlush(entry);
        } catch(DataIntegrityViolationException ex) {
            throw new DuplicateEntryException(tracking,prn);
        }
        audit.record(actorId,"UPDATE","ENTRY",Long.toString(id),
                "Updated "+entry.date+" / "+entry.trackingNumber+" / "+entry.prn+" / UGX "+entry.amount);
        return EntryView.of(entry);
    }

    private void validateDate(LocalDate date) {
        if(date.isAfter(LocalDate.now(ZONE)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date cannot be in the future");
    }

    private long parseAmount(String amountText) {
        try {
            return Long.parseLong(amountText);
        } catch(NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Amount must be a positive whole number of UGX within the supported range");
        }
    }

    private void duplicate(CollectionEntry entry,String field) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,duplicateMessage(entry,field));
    }

    private static String duplicateMessage(CollectionEntry entry,String field) {
        return "Duplicate "+field+"; first recorded on "+entry.date+" by "+entry.createdBy.displayName+
                " ("+entry.trackingNumber+", PRN "+entry.prn+")";
    }

    @Transactional(readOnly=true) public Page<EntryView> list(String username,boolean admin,int page,int size,
            String q,LocalDate from,LocalDate to,Long officerId) {
        if(page<0||size<1||size>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"page must be >=0 and size 1..100");
        if(!admin&&officerId!=null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Officers cannot select another officer");
        if(from!=null&&to!=null&&to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"From date must not be after To date");
        Long ownId=accounts.findByUsernameIgnoreCase(username).orElseThrow().id;
        Specification<CollectionEntry> spec=(root,query,cb)->cb.conjunction();
        if(!admin) spec=spec.and((root,query,cb)->cb.equal(root.get("createdBy").get("id"),ownId));
        if(admin&&officerId!=null) spec=spec.and((root,query,cb)->cb.equal(root.get("createdBy").get("id"),officerId));
        if(q!=null&&!q.isBlank()) {
            String pattern="%"+q.trim().toLowerCase(Locale.ROOT)+"%";
            spec=spec.and((root,query,cb)->cb.or(cb.like(cb.lower(root.get("trackingNumber")),pattern),
                    cb.like(cb.lower(root.get("prn")),pattern)));
        }
        if(from!=null) spec=spec.and((root,query,cb)->cb.greaterThanOrEqualTo(root.get("date"),from));
        if(to!=null) spec=spec.and((root,query,cb)->cb.lessThanOrEqualTo(root.get("date"),to));
        return entries.findAll(spec,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"date").and(Sort.by(Sort.Direction.DESC,"id"))))
                .map(EntryView::of);
    }

    @Transactional public void delete(long id,boolean admin,String username,Long actorId) {
        CollectionEntry entry=entries.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Entry not found"));
        if(!admin&&!entry.createdBy.username.equalsIgnoreCase(username))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"You can only delete your own entries");
        audit.record(actorId,"DELETE","ENTRY",Long.toString(id),
                "Deleted "+entry.date+" / "+entry.trackingNumber+" / "+entry.prn+" / UGX "+entry.amount);
        entries.delete(entry);
    }

    public DashboardSummary dashboard(String username,boolean admin,LocalDate selectedDate,Long officerId) {
        LocalDate today=LocalDate.now(ZONE);
        LocalDate selected=selectedDate==null?today:selectedDate;
        if(selected.isAfter(today)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date cannot be in the future");
        if(!admin&&officerId!=null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Officers cannot select another officer");
        Long ownerId=admin?null:accounts.findByUsernameIgnoreCase(username).orElseThrow().id;
        LocalDate currentFriday=today.minusDays((today.getDayOfWeek().getValue()-DayOfWeek.FRIDAY.getValue()+7)%7);
        LocalDate monthStart=today.withDayOfMonth(1),monthEnd=today.withDayOfMonth(today.lengthOfMonth());
        return new DashboardSummary(selected,
                summary(selected,selected,"Daily",ownerId,officerId),
                summary(currentFriday,currentFriday.plusDays(6),"Friday–Thursday",ownerId,officerId),
                summary(monthStart,monthEnd,"Monthly",ownerId,officerId),
                breakdown(currentFriday,currentFriday.plusDays(6),ownerId,officerId),
                breakdown(monthStart,monthEnd,ownerId,officerId));
    }

    public Summary summary(String username,boolean admin,String period,LocalDate from,LocalDate to,Long officerId) {
        LocalDate today=LocalDate.now(ZONE);
        PeriodWindow window;
        try { window=PeriodWindow.forPeriod(period,from,to,today); }
        catch(IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,ex.getMessage()); }
        if(!admin&&officerId!=null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Officers cannot select another officer");
        Long ownerId=admin?null:accounts.findByUsernameIgnoreCase(username).orElseThrow().id;
        return summary(window.start(),window.end(),window.label(),ownerId,officerId);
    }

    public long filteredTotal(String username,boolean admin,String q,LocalDate from,LocalDate to,Long officerId) {
        if(!admin&&officerId!=null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Officers cannot select another officer");
        if(from!=null&&to!=null&&to.isBefore(from)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"From date must not be after To date");
        Long ownerId=admin?null:accounts.findByUsernameIgnoreCase(username).orElseThrow().id;
        Long total=entries.filteredTotal(ownerId,officerId,from,to,q==null||q.isBlank()?null:q.trim());
        return total==null?0:total;
    }

    private Summary summary(LocalDate start,LocalDate end,String label,Long ownerId,Long officerId) {
        Object[] totals=entries.totals(start,end,ownerId,officerId);
        return new Summary(((Number)totals[0]).longValue(),totals[1].toString(),
                start.toString(),end.toString(),label);
    }

    private List<DailyTotal> breakdown(LocalDate start,LocalDate end,Long ownerId,Long officerId) {
        Map<LocalDate,DailyTotal> found=new HashMap<>();
        for(Object[] row:entries.dailyTotals(start,end,ownerId,officerId)) {
            LocalDate date=(LocalDate)row[0];
            found.put(date,new DailyTotal(date,((Number)row[1]).longValue(),row[2].toString()));
        }
        List<DailyTotal> result=new ArrayList<>();
        for(LocalDate date=start;!date.isAfter(end);date=date.plusDays(1))
            result.add(found.getOrDefault(date,new DailyTotal(date,0,"0")));
        return result;
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
        String field=found.trackingNumber.equalsIgnoreCase(ex.trackingNumber)?"tracking number":"PRN";
        return EntryService.duplicateMessage(found,field);
    }
}

record PeriodWindow(String label,LocalDate start,LocalDate end) {
    static PeriodWindow forPeriod(String period,LocalDate from,LocalDate to,LocalDate today) {
        if("week".equalsIgnoreCase(period)) {
            int sinceFriday=(today.getDayOfWeek().getValue()-DayOfWeek.FRIDAY.getValue()+7)%7;
            LocalDate friday=today.minusDays(sinceFriday);
            return new PeriodWindow("Friday–Thursday",friday,friday.plusDays(6));
        }
        if("month".equalsIgnoreCase(period))
            return new PeriodWindow("Calendar month",today.withDayOfMonth(1),today.withDayOfMonth(today.lengthOfMonth()));
        if("day".equalsIgnoreCase(period)) return new PeriodWindow("Today",today,today);
        if("custom".equalsIgnoreCase(period)&&from!=null&&to!=null&&!to.isBefore(from))
            return new PeriodWindow("Custom",from,to);
        throw new IllegalArgumentException("period must be day, week, month, or custom with valid from and to dates");
    }
}
