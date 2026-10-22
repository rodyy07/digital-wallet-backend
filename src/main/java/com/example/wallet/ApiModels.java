package com.example.wallet;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class ApiModels {
    private ApiModels() {}
    public record CreateUser(@NotBlank String name, @Email @NotBlank String email, @NotBlank String phone) {}
    public record CreateWallet(@NotBlank @Size(max=80) String name) {}
    public record TransferRequest(@Positive long sourceAccountId, @Positive long destinationAccountId,
                                  @Positive long amountPaise, String note) {}
    public record TopupRequest(@Positive long destinationAccountId, @Positive long amountPaise, String note) {}
    public record WithdrawRequest(@Positive long sourceAccountId, @Positive long amountPaise, String note) {}
    public record TransferResponse(long transferId, String status, long amountPaise) {}
    public record BalanceResponse(long accountId, long balancePaise, String currency) {}
}
