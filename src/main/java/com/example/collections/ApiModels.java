package com.example.collections;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;

record LoginRequest(@NotBlank @Size(max=80) String username, @NotBlank @Size(max=128) String password) {}
record PasswordRequest(@NotBlank @Size(min=12,max=128) String newPassword) {}
record LoginResponse(String token, String username, String displayName, String role, boolean mustChangePassword) {}
record AccountRequest(@NotBlank @Size(max=80) String username, @NotBlank @Size(max=160) String displayName,
                      @NotBlank @Pattern(regexp="ADMIN|OFFICER") String role,
                      @NotBlank @Size(min=12,max=128) String password) {}
record EntryRequest(@NotBlank @Size(max=160) String clientName, @NotBlank @Size(max=80) String trackingNumber,
                    @NotBlank @Size(max=80) String prn, @NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal amount,
                    @NotNull Instant collectedAt, @Size(max=80) String receiptNumber, @Size(max=1000) String notes) {}
record EntryView(Long id, String clientName, String trackingNumber, String prn, BigDecimal amount, Instant collectedAt,
                 String receiptNumber, String notes, String createdBy, Instant createdAt) {
    static EntryView of(CollectionEntry e) {
        return new EntryView(e.id,e.clientName,e.trackingNumber,e.prn,e.amount,e.collectedAt,
                e.receiptNumber,e.notes,e.createdBy.username,e.createdAt);
    }
}
record Summary(long count, BigDecimal amount, String start, String end, String label) {}
