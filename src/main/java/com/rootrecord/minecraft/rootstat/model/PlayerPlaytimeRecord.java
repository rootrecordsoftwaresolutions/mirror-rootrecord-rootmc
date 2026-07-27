package com.rootrecord.minecraft.rootstat.model;

public record PlayerPlaytimeRecord(
        String uuid,
        String username,
        long totalPlaytimeSeconds,
        String firstJoinAt,
        String lastLoginAt) {}
