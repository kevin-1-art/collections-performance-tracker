package com.example.collections;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountServiceTest {
    private final AccountRepository accounts=mock(AccountRepository.class);
    private final PasswordEncoder passwords=mock(PasswordEncoder.class);
    private final TokenService tokens=mock(TokenService.class);
    private final AuditService audit=mock(AuditService.class);
    private final AccountService service=new AccountService(accounts,passwords,tokens,audit);

    @Test void updatesUsernameDisplayNameAndRoleAndAuditsTheChange() {
        Account account=new Account();
        account.id=4L;
        account.username="old.name";
        account.displayName="Old Name";
        account.role="OFFICER";
        when(accounts.findById(4L)).thenReturn(Optional.of(account));
        when(accounts.findByUsernameIgnoreCase("new.name")).thenReturn(Optional.empty());
        when(accounts.saveAndFlush(account)).thenReturn(account);

        Account updated=service.update(4L,new AccountUpdateRequest(" new.name "," New Name ","ADMIN"),1L);

        assertEquals("new.name",updated.username);
        assertEquals("New Name",updated.displayName);
        assertEquals("ADMIN",updated.role);
        verify(audit).record(1L,"UPDATE","ACCOUNT","4","Updated account identity or role");
    }

    @Test void rejectsDuplicateUsernameWithoutSaving() {
        Account account=new Account();
        account.id=4L;
        account.username="old.name";
        account.displayName="Old Name";
        account.role="OFFICER";
        Account duplicate=new Account();
        duplicate.username="new.name";
        when(accounts.findById(4L)).thenReturn(Optional.of(account));
        when(accounts.findByUsernameIgnoreCase("new.name")).thenReturn(Optional.of(duplicate));

        ResponseStatusException error=assertThrows(ResponseStatusException.class,
                ()->service.update(4L,new AccountUpdateRequest("new.name","New Name","OFFICER"),1L));

        assertEquals(409,error.getStatusCode().value());
        verify(accounts,never()).saveAndFlush(any());
    }

    @Test void administratorCannotDemoteSelf() {
        Account account=new Account();
        account.id=4L;
        account.username="admin";
        account.displayName="Admin";
        account.role="ADMIN";
        when(accounts.findById(4L)).thenReturn(Optional.of(account));

        ResponseStatusException error=assertThrows(ResponseStatusException.class,
                ()->service.update(4L,new AccountUpdateRequest("admin","Admin","OFFICER"),4L));

        assertEquals(400,error.getStatusCode().value());
        verify(accounts,never()).saveAndFlush(any());
    }

    @Test void cannotDemoteTheLastEnabledAdministrator() {
        Account account=new Account();
        account.id=4L;
        account.username="admin";
        account.displayName="Admin";
        account.role="ADMIN";
        account.enabled=true;
        when(accounts.findById(4L)).thenReturn(Optional.of(account));
        when(accounts.countByRoleAndEnabledTrue("ADMIN")).thenReturn(1L);

        ResponseStatusException error=assertThrows(ResponseStatusException.class,
                ()->service.update(4L,new AccountUpdateRequest("admin","Admin","OFFICER"),5L));

        assertEquals(400,error.getStatusCode().value());
        verify(accounts,never()).saveAndFlush(any());
    }

    @Test void cannotDeactivateTheLastEnabledAdministrator() {
        Account account=new Account();
        account.id=4L;
        account.username="admin";
        account.role="ADMIN";
        account.enabled=true;
        when(accounts.findById(4L)).thenReturn(Optional.of(account));
        when(accounts.countByRoleAndEnabledTrue("ADMIN")).thenReturn(1L);

        ResponseStatusException error=assertThrows(ResponseStatusException.class,
                ()->service.setEnabled(4L,false,5L));

        assertEquals(400,error.getStatusCode().value());
        verify(accounts,never()).save(account);
    }
}
