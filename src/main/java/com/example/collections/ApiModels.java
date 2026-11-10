package com.example.collections;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.Instant;

record LoginRequest(@NotBlank @Size(max=80) String username, @NotBlank @Size(max=128) String password) {}
record RegisterRequest(@NotBlank @Size(max=80) String username, @NotBlank @Size(max=160) String displayName,
                       @NotBlank @Size(min=12,max=128) String password) {}
record PasswordRequest(@NotBlank @Size(min=12,max=128) String newPassword) {}
record ResetPasswordRequest(@NotBlank @Size(min=12,max=128) String temporaryPassword) {}
record AccountUpdateRequest(@NotBlank @Size(max=80) String username, @NotBlank @Size(max=160) String displayName,
                            @NotBlank @Pattern(regexp="ADMIN|OFFICER") String role) {}
record LoginResponse(String token, String username, String displayName, String role, boolean mustChangePassword) {}
record AccountRequest(@NotBlank @Size(max=80) String username, @NotBlank @Size(max=160) String displayName,
                      @NotBlank @Pattern(regexp="ADMIN|OFFICER") String role,
                      @NotBlank @Size(min=12,max=128) String password) {}
record EntryRequest(@NotNull LocalDate date,
                    @NotBlank @Pattern(regexp="(?i)^G\\d{6}-\\d{4}$") String trackingNumber,
                    @NotBlank @Pattern(regexp="^\\d{13}$") String prn,
                    @NotBlank @Pattern(regexp="^[1-9]\\d{0,18}$") String amount) {}
record EntryView(Long id, LocalDate date, String trackingNumber, String prn, String amount,
                 String officer, Long officerId, Instant createdAt) {
    static EntryView of(CollectionEntry e) {
        return new EntryView(e.id,e.date,e.trackingNumber,e.prn,e.amount.toString(),
                e.createdBy.displayName,e.createdBy.id,e.createdAt);
    }
}
record Summary(long count, String amount, String start, String end, String label) {}
record DailyTotal(LocalDate date, long count, String amount) {}
record DashboardSummary(LocalDate selectedDate, Summary daily, Summary weekly, Summary monthly,
                        java.util.List<DailyTotal> weekBreakdown, java.util.List<DailyTotal> monthBreakdown) {}
