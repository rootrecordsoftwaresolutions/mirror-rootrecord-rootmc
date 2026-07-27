package com.rootrecord.minecraft.rootmc.ingame;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class IngameEventBuffer {

    public record PendingEvent(
            String minecraftUuid,
            String minecraftUsername,
            String eventType,
            String worldName,
            String dimension,
            double x,
            double y,
            double z,
            String label,
            String body,
            String createdAt) {}

    private final ConcurrentLinkedQueue<PendingEvent> queue = new ConcurrentLinkedQueue<>();
    private final int maxPending;

    public IngameEventBuffer(int maxPending) {
        this.maxPending = Math.max(8, maxPending);
    }

    public void enqueue(PendingEvent event) {
        queue.add(event);
        while (queue.size() > maxPending) {
            queue.poll();
        }
    }

    public List<PendingEvent> drain() {
        List<PendingEvent> out = new ArrayList<>();
        PendingEvent next;
        while ((next = queue.poll()) != null) {
            out.add(next);
        }
        return out;
    }

    public static PendingEvent waypoint(
            String uuid,
            String username,
            String world,
            String dimension,
            double x,
            double y,
            double z,
            String label) {
        return new PendingEvent(
                uuid,
                username,
                "waypoint",
                world,
                dimension,
                x,
                y,
                z,
                label,
                null,
                Instant.now().toString());
    }

    public static PendingEvent note(
            String uuid,
            String username,
            String world,
            String dimension,
            double x,
            double y,
            double z,
            String body) {
        return new PendingEvent(
                uuid,
                username,
                "note",
                world,
                dimension,
                x,
                y,
                z,
                null,
                body,
                Instant.now().toString());
    }
}
