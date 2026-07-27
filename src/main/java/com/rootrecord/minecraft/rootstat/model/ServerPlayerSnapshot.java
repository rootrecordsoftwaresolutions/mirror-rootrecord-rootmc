package com.rootrecord.minecraft.rootstat.model;

import java.util.Map;

public record ServerPlayerSnapshot(
        String uuid,
        String username,
        Integer powerLevel,
        Map<String, Integer> skills,
        Long playtimeSeconds,
        String firstJoinAt,
        String lastLoginAt) {}
