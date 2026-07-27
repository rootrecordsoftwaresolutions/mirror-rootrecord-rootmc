package com.rootrecord.minecraft.rootstat.model;

public record LinkedPlayer(
        String uuid,
        String username,
        String accountId,
        String email,
        String verifiedAt,
        String updatedAt) {

    public boolean verified() {
        return accountId != null && !accountId.isBlank();
    }
}
