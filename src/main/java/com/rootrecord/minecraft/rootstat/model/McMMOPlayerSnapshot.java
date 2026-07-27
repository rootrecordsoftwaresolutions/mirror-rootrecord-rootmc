package com.rootrecord.minecraft.rootstat.model;

import java.util.Map;

public record McMMOPlayerSnapshot(
        String uuid,
        String username,
        int powerLevel,
        Map<String, Integer> skills) {}
