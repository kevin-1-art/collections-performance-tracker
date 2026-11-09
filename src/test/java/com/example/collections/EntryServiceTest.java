package com.example.collections;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EntryServiceTest {
    private final EntryRepository entries=mock(EntryRepository.class);
    private final AccountRepository accounts=mock(AccountRepository.class);
    private final AuditService audit=mock(AuditService.class);
    private final EntryService service=new EntryService(entries,accounts,audit);

    @Test void duplicatePrecheckNamesFirstRecordedDateAndOfficer() {
        Account officer=new Account();
        officer.displayName="Amina";
        CollectionEntry existing=new CollectionEntry();
        existing.date=LocalDate.of(2026,5,4);
        existing.trackingNumber="G240712-2665";
        existing.prn="2270005999848";
        existing.createdBy=officer;
        when(entries.findFirstByTrackingNumberIgnoreCase("G240712-2665")).thenReturn(Optional.of(existing));

        ResponseStatusException exception=assertThrows(ResponseStatusException.class,()->service.create(
                new EntryRequest(LocalDate.of(2026,5,5),"G240712-2665","2270005999849","100"),"officer",1L));
        assertTrue(exception.getReason().contains("2026-05-04"));
        assertTrue(exception.getReason().contains("Amina"));
        verify(entries,never()).saveAndFlush(any());
    }

    @Test void uniqueConstraintRaceBecomesAConflictWithExistingRecordDetails() {
        Account owner=new Account();
        owner.username="officer";
        when(entries.findFirstByTrackingNumberIgnoreCase("G240712-2665")).thenReturn(Optional.empty());
        when(entries.findFirstByPrnIgnoreCase("2270005999848")).thenReturn(Optional.empty());
        when(accounts.findByUsernameIgnoreCase("officer")).thenReturn(Optional.of(owner));
        when(entries.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique"));

        DuplicateEntryException exception=assertThrows(DuplicateEntryException.class,()->service.create(
                new EntryRequest(LocalDate.of(2026,5,5),"G240712-2665","2270005999848","100"),"officer",1L));
        Account recordedBy=new Account();
        recordedBy.displayName="Amina";
        CollectionEntry existing=new CollectionEntry();
        existing.date=LocalDate.of(2026,5,4);
        existing.trackingNumber=exception.trackingNumber;
        existing.prn=exception.prn;
        existing.createdBy=recordedBy;
        when(entries.findFirstByTrackingNumberIgnoreCase(exception.trackingNumber)).thenReturn(Optional.of(existing));
        assertTrue(DuplicateEntryAdvice.details(exception,entries).contains("Amina"));
    }

    @Test void lowercaseTrackingNumberIsNormalizedBeforeSaving() {
        Account owner=new Account();
        owner.username="officer";
        owner.displayName="Amina";
        owner.id=1L;
        when(entries.findFirstByTrackingNumberIgnoreCase("G240712-2665")).thenReturn(Optional.empty());
        when(entries.findFirstByPrnIgnoreCase("2270005999848")).thenReturn(Optional.empty());
        when(accounts.findByUsernameIgnoreCase("officer")).thenReturn(Optional.of(owner));
        when(entries.saveAndFlush(any())).thenAnswer(invocation->{
            CollectionEntry entry=invocation.getArgument(0);
            entry.id=10L;
            return entry;
        });

        EntryView created=service.create(new EntryRequest(LocalDate.of(2026,5,5),
                "g240712-2665","2270005999848","100"),"officer",owner.id);

        assertEquals("G240712-2665",created.trackingNumber());
        verify(audit).record(owner.id,"CREATE","ENTRY","10","Added G240712-2665 / 2270005999848 / 100");
    }

    @Test void rejectsFutureDatesUsingKampalaToday() {
        EntryRequest request=new EntryRequest(LocalDate.now(EntryService.ZONE).plusDays(1),
                "G240712-2665","2270005999848","100");
        ResponseStatusException exception=assertThrows(ResponseStatusException.class,
                ()->service.create(request,"officer",1L));
        assertEquals(400,exception.getStatusCode().value());
    }
}
