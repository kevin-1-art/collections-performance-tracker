package com.example.collections;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

@Service
class AccountService implements ApplicationRunner {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuditService audit;
    @Value("${app.bootstrap-admin-username:admin}") String adminName;
    @Value("${app.bootstrap-admin-password:}") String adminPassword;
    @Value("${app.bootstrap-admin-display-name:System Administrator}") String adminDisplayName;
    AccountService(AccountRepository accounts, PasswordEncoder passwords, TokenService tokens, AuditService audit) {
        this.accounts=accounts; this.passwords=passwords; this.tokens=tokens; this.audit=audit;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        if (adminPassword != null && !adminPassword.isBlank() && accounts.findByUsernameIgnoreCase(adminName).isEmpty()) {
            if (adminPassword.length()<12) throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must contain at least 12 characters");
            Account admin=new Account(); admin.username=adminName.trim(); admin.displayName=adminDisplayName;
            admin.passwordHash=passwords.encode(adminPassword); admin.role="ADMIN"; admin.mustChangePassword=true;
            accounts.save(admin);
        }
    }
    @Transactional(noRollbackFor=ResponseStatusException.class) public LoginResponse login(LoginRequest request) {
        Account account=accounts.findForLogin(request.username().trim()).orElse(null);
        if (account==null || !account.enabled) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid username or password");
        if (account.lockedUntil!=null && account.lockedUntil.isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.LOCKED,"Account locked until "+account.lockedUntil);
        if (!passwords.matches(request.password(),account.passwordHash)) {
            account.failedAttempts++;
            if (account.failedAttempts>=5) { account.lockedUntil=Instant.now().plusSeconds(15*60); account.failedAttempts=0; }
            accounts.save(account);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid username or password");
        }
        account.failedAttempts=0; account.lockedUntil=null; accounts.save(account);
        return new LoginResponse(tokens.issue(account),account.username,account.displayName,account.role,account.mustChangePassword);
    }
    @Transactional public void changePassword(String username, PasswordRequest request) {
        Account a=accounts.findByUsernameIgnoreCase(username).orElseThrow();
        if (passwords.matches(request.newPassword(),a.passwordHash))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"New password must differ from current password");
        a.passwordHash=passwords.encode(request.newPassword()); a.mustChangePassword=false; accounts.save(a);
    }
    @Transactional public Account create(AccountRequest request, Long actorId) {
        if (accounts.findByUsernameIgnoreCase(request.username().trim()).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Username already exists");
        Account a=new Account(); a.username=request.username().trim(); a.displayName=request.displayName().trim();
        a.role=request.role(); a.passwordHash=passwords.encode(request.password()); a.mustChangePassword=true;
        a=accounts.save(a); audit.record(actorId,"CREATE","ACCOUNT",a.id.toString(),"Created "+a.username); return a;
    }
    @Transactional public void setEnabled(long id,boolean enabled,long actorId) {
        Account account=accounts.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Account not found"));
        account.enabled=enabled; accounts.save(account);
        audit.record(actorId,"UPDATE","ACCOUNT",Long.toString(id),"Set enabled="+enabled);
    }
}
