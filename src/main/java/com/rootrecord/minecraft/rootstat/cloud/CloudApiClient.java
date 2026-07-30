package com.rootrecord.minecraft.rootstat.cloud;

import com.rootrecord.minecraft.rootstat.config.RootStatConfig;
import com.rootrecord.minecraft.rootstat.model.LinkedPlayer;
import com.rootrecord.minecraft.rootstat.model.LinkStartResult;
import com.rootrecord.minecraft.rootstat.model.McMMOPlayerSnapshot;
import com.rootrecord.minecraft.rootstat.economy.EconomySnapshot;
import com.rootrecord.minecraft.rootstat.economy.GoldBreakdown;
import com.rootrecord.minecraft.rootstat.economy.PhysicalGoldStorageScanner;
import com.rootrecord.minecraft.rootstat.model.ServerPlayerSnapshot;

import java.io.IOException;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CloudApiClient {

    private static final Pattern JSON_BOOL = Pattern.compile("\"linked\"\\s*:\\s*(true|false)");
    private static final Pattern JSON_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_URL = Pattern.compile("\"verify_url\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private RootStatConfig config;

    public CloudApiClient(RootStatConfig config) {
        this.config = config;
    }

    public void updateConfig(RootStatConfig config) {
        this.config = config;
    }

    public LinkStartResult startLink(String uuid, String username) throws IOException, InterruptedException {
        String body = "{\"uuid\":\"" + escapeJson(uuid) + "\",\"username\":\"" + escapeJson(username) + "\"}";
        String json = post("/api/realm/minecraft/link/start", body);
        Matcher code = JSON_CODE.matcher(json);
        Matcher url = JSON_URL.matcher(json);
        if (!code.find() || !url.find()) {
            throw new IOException("Unexpected link/start response");
        }
        return new LinkStartResult(code.group(1), url.group(1));
    }

    public LinkStatus linkStatus(String uuid) throws IOException, InterruptedException {
        String json = get("/api/realm/minecraft/link/status?uuid=" + uuid);
        Matcher linked = JSON_BOOL.matcher(json);
        if (!linked.find()) {
            return LinkStatus.unlinked();
        }
        if (!"true".equals(linked.group(1))) {
            return LinkStatus.unlinked();
        }
        boolean discordLinked = json.contains("\"discord_linked\":true");
        boolean proUnlocked = json.contains("\"pro_unlocked\":true");
        boolean lifeMember = json.contains("\"life_member\":true");
        boolean topActivePlayer = json.contains("\"top_active_player\":true");
        return new LinkStatus(
                true,
                discordLinked,
                extractString(json, "account_id"),
                extractString(json, "email"),
                extractString(json, "minecraft_username"),
                extractString(json, "verified_at"),
                extractString(json, "discord_user_id"),
                proUnlocked,
                lifeMember,
                topActivePlayer);
    }

    public GovernanceVotingPower fetchGovernanceVotingPower(String uuid) throws IOException, InterruptedException {
        String json = get("/api/realm/minecraft/governance/voting-power?uuid=" + uuid);
        boolean ok = json.contains("\"ok\":true");
        boolean eligible = json.contains("\"eligible\":true");
        double sharePercent = parseJsonNumber(json, "share_percent");
        String summary = extractString(json, "summary");
        String votingChannelUrl = extractString(json, "voting_channel_url");
        String constitutionUrl = extractString(json, "constitution_url");
        return new GovernanceVotingPower(ok, eligible, sharePercent, summary, votingChannelUrl, constitutionUrl);
    }

    public List<LinkedPlayer> sync(String sinceIso) throws IOException, InterruptedException {
        String path = sinceIso == null || sinceIso.isBlank()
                ? "/api/realm/minecraft/sync"
                : "/api/realm/minecraft/sync?since=" + sinceIso;
        String json = get(path);
        List<LinkedPlayer> out = new ArrayList<>();
        int idx = 0;
        while (true) {
            int start = json.indexOf("\"minecraft_uuid\"", idx);
            if (start < 0) {
                break;
            }
            String slice = json.substring(start, Math.min(json.length(), start + 400));
            String uuid = extractString(slice, "minecraft_uuid");
            String uname = extractString(slice, "minecraft_username");
            String account = extractString(slice, "account_id");
            String email = extractString(slice, "email");
            String verifiedAt = extractString(slice, "verified_at");
            String updatedAt = extractString(slice, "updated_at");
            if (uuid != null) {
                out.add(new LinkedPlayer(uuid, uname, account, email, verifiedAt, updatedAt));
            }
            idx = start + 20;
        }
        return out;
    }

    public void syncEconomy(EconomySnapshot snapshot) throws IOException, InterruptedException {
        if (snapshot == null) {
            return;
        }
        String syncedAt = Instant.now().toString();
        post("/api/realm/minecraft/economy/sync", buildEconomySyncBody(snapshot, syncedAt));
    }

    public void syncPhysicalGoldStorage(PhysicalGoldStorageScanner.ScanResult result)
            throws IOException, InterruptedException {
        if (result == null) {
            return;
        }
        String syncedAt = Instant.now().toString();
        post("/api/realm/minecraft/economy/physical-gold", buildPhysicalGoldBody(result, syncedAt));
    }

    private static String buildPhysicalGoldBody(PhysicalGoldStorageScanner.ScanResult result, String syncedAt) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"synced_at\":\"").append(escapeJson(syncedAt)).append('"');
        sb.append(",\"total_g\":").append(result.totalG());
        sb.append(",\"unattributed_g\":").append(result.unattributedG());
        appendBreakdown(sb, "unattributed", result.unattributed());
        sb.append(",\"shops_scanned\":").append(result.shopsScanned());
        sb.append(",\"chunks_scanned\":").append(result.chunksScanned());
        sb.append(",\"towny_blocks_scanned\":").append(result.townyBlocksScanned());
        sb.append(",\"players\":[");
        for (int i = 0; i < result.players().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            PhysicalGoldStorageScanner.PlayerRow row = result.players().get(i);
            sb.append("{\"minecraft_uuid\":\"").append(escapeJson(row.uuid())).append('"');
            if (row.username() != null && !row.username().isBlank()) {
                sb.append(",\"minecraft_username\":\"").append(escapeJson(row.username())).append('"');
            }
            sb.append(",\"inventory_g\":").append(row.inventoryG());
            sb.append(",\"ender_g\":").append(row.enderG());
            sb.append(",\"shop_g\":").append(row.shopG());
            sb.append(",\"chest_g\":").append(row.chestG());
            sb.append(",\"towny_placed_g\":").append(row.townyPlacedG());
            sb.append(",\"total_g\":").append(row.totalG());
            appendBreakdown(sb, "inv", row.inventory());
            appendBreakdown(sb, "ender", row.ender());
            appendBreakdown(sb, "chest", row.chest());
            appendBreakdown(sb, "shop", row.shop());
            appendBreakdown(sb, "towny", row.towny());
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendBreakdown(StringBuilder sb, String prefix, GoldBreakdown breakdown) {
        if (breakdown == null) {
            sb.append(",\"").append(prefix).append("_nugget_g\":0");
            sb.append(",\"").append(prefix).append("_ingot_g\":0");
            sb.append(",\"").append(prefix).append("_block_g\":0");
            sb.append(",\"").append(prefix).append("_other_g\":0");
            return;
        }
        sb.append(",\"").append(prefix).append("_nugget_g\":").append(breakdown.nuggetG);
        sb.append(",\"").append(prefix).append("_ingot_g\":").append(breakdown.ingotG);
        sb.append(",\"").append(prefix).append("_block_g\":").append(breakdown.blockG);
        sb.append(",\"").append(prefix).append("_other_g\":").append(breakdown.otherG);
    }

    /** Upsert or delete one shop listing; recomputes market avg for affected item(s) server-side. */
    public void syncShopListing(
            EconomySnapshot.ShopListingRow row,
            boolean delete,
            String shopId,
            String itemKey,
            String previousItemKey) throws IOException, InterruptedException {
        String syncedAt = Instant.now().toString();
        post("/api/realm/minecraft/economy/shop-listing", buildShopListingBody(row, delete, shopId, itemKey, previousItemKey, syncedAt));
    }

    public void syncIngameEvents(List<com.rootrecord.minecraft.rootmc.ingame.IngameEventBuffer.PendingEvent> events)
            throws IOException, InterruptedException {
        if (events == null || events.isEmpty()) {
            return;
        }
        post("/api/rootmc/ingame-events", buildIngameEventsBody(events));
    }

    public record ChatRelayMessage(String username, String minecraftUuid, String message, String kind, String createdAt) {}

    public void relayIngameChat(List<ChatRelayMessage> messages) throws IOException, InterruptedException {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        post("/api/rootmc/ingame-chat", buildIngameChatBody(messages));
    }

    public record DiscordInboundMessage(String id, String username, String message) {}

    public record DiscordChatPoll(List<DiscordInboundMessage> messages, String newestId) {}

    public DiscordChatPoll pollDiscordChat(String afterMessageId) throws IOException, InterruptedException {
        String path = "/api/rootmc/ingame-chat/poll";
        if (afterMessageId != null && !afterMessageId.isBlank()) {
            path += "?after=" + java.net.URLEncoder.encode(afterMessageId, java.nio.charset.StandardCharsets.UTF_8);
        }
        String json = get(path);
        List<DiscordInboundMessage> messages = new ArrayList<>();
        int arrayStart = json.indexOf("\"messages\"");
        if (arrayStart >= 0) {
            int open = json.indexOf('[', arrayStart);
            int close = json.indexOf(']', open);
            if (open >= 0 && close > open) {
                String arrayBody = json.substring(open + 1, close);
                int idx = 0;
                while (idx < arrayBody.length()) {
                    int objStart = arrayBody.indexOf('{', idx);
                    if (objStart < 0) {
                        break;
                    }
                    int depth = 0;
                    int objEnd = -1;
                    for (int i = objStart; i < arrayBody.length(); i++) {
                        char c = arrayBody.charAt(i);
                        if (c == '{') {
                            depth++;
                        } else if (c == '}') {
                            depth--;
                            if (depth == 0) {
                                objEnd = i + 1;
                                break;
                            }
                        }
                    }
                    if (objEnd < 0) {
                        break;
                    }
                    String chunk = arrayBody.substring(objStart, objEnd);
                    String id = extractString(chunk, "id");
                    String username = extractString(chunk, "username");
                    String message = extractString(chunk, "message");
                    if (id != null && message != null) {
                        messages.add(new DiscordInboundMessage(id, username, message));
                    }
                    idx = objEnd;
                }
            }
        }
        String newestId = extractString(json, "newest_id");
        return new DiscordChatPoll(messages, newestId);
    }

    private static String buildIngameChatBody(List<ChatRelayMessage> messages) {
        StringBuilder sb = new StringBuilder("{\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ChatRelayMessage m = messages.get(i);
            sb.append("{\"username\":\"").append(escapeJson(m.username())).append('"');
            if (m.minecraftUuid() != null && !m.minecraftUuid().isBlank()) {
                sb.append(",\"minecraft_uuid\":\"").append(escapeJson(m.minecraftUuid())).append('"');
            }
            sb.append(",\"message\":\"").append(escapeJson(m.message())).append('"');
            if (m.kind() != null && !m.kind().isBlank()) {
                sb.append(",\"kind\":\"").append(escapeJson(m.kind())).append('"');
            }
            if (m.createdAt() != null && !m.createdAt().isBlank()) {
                sb.append(",\"created_at\":\"").append(escapeJson(m.createdAt())).append('"');
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public void syncTownySnapshot(java.util.Map<String, Object> snapshot)
            throws IOException, InterruptedException {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        post("/api/rootmc/towny/sync", buildTownySyncBody(snapshot));
    }

    private static String buildTownySyncBody(java.util.Map<String, Object> snapshot) {
        StringBuilder sb = new StringBuilder("{");
        appendTownyArray(sb, "towns", snapshot.get("towns"));
        sb.append(',');
        appendTownyArray(sb, "nations", snapshot.get("nations"));
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendTownyArray(StringBuilder sb, String key, Object value) {
        sb.append('"').append(key).append("\":[");
        if (value instanceof Iterable<?> iterable) {
            boolean first = true;
            for (Object item : iterable) {
                if (!(item instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(mapToJson((java.util.Map<String, Object>) map));
            }
        }
        sb.append(']');
    }

    private static String mapToJson(java.util.Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJson(entry.getKey())).append("\":");
            Object v = entry.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escapeJson(String.valueOf(v))).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    public VaultClaimResult claimVaultOrders(String minecraftUuid)
            throws IOException, InterruptedException {
        String body = "{\"minecraft_uuid\":\"" + escapeJson(minecraftUuid) + "\"}";
        String json = post("/api/rootmc/vault/claim", body);
        return VaultClaimResult.parse(json);
    }

    /** Redeem a tradeable Pro voucher (one_month / lifetime). Returns raw JSON. */
    public String redeemProVoucher(String minecraftUuid, String voucherId)
            throws IOException, InterruptedException {
        String body = "{\"minecraft_uuid\":\"" + escapeJson(minecraftUuid)
                + "\",\"voucher_id\":\"" + escapeJson(voucherId) + "\"}";
        return post("/api/realm/minecraft/memberships/redeem-voucher", body);
    }

    public record GoldTransfer(
            String id,
            String fromUuid,
            String toUuid,
            double amount,
            String source,
            String toUsername) {}

    public record GoldTransferResult(String id, String status, String error) {}

    public List<GoldTransfer> fetchPendingGoldTransfers() throws IOException, InterruptedException {
        String json = get("/api/rootmc/economy/transfers/pending");
        List<GoldTransfer> out = new ArrayList<>();
        int idx = 0;
        while (idx < json.length()) {
            int idPos = json.indexOf("\"id\"", idx);
            if (idPos < 0) {
                break;
            }
            String chunk = json.substring(idPos, Math.min(json.length(), idPos + 480));
            String id = extractString(chunk, "id");
            if (id == null || id.isBlank()) {
                break;
            }
            String fromUuid = extractString(chunk, "from_uuid");
            String toUuid = extractString(chunk, "to_uuid");
            double amount = parseJsonNumber(chunk, "amount");
            String source = extractString(chunk, "source");
            String toUsername = extractString(chunk, "to_username");
            if (fromUuid != null && toUuid != null && amount >= 0.01d) {
                out.add(new GoldTransfer(id, fromUuid, toUuid, amount, source, toUsername));
            }
            idx = idPos + 4;
            if (out.size() >= 200) {
                break;
            }
        }
        return out;
    }

    public void completeGoldTransfers(List<GoldTransferResult> results)
            throws IOException, InterruptedException {
        if (results == null || results.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("{\"transfers\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            GoldTransferResult r = results.get(i);
            sb.append("{\"id\":\"").append(escapeJson(r.id())).append('"');
            sb.append(",\"status\":\"").append(escapeJson(r.status())).append('"');
            if (r.error() != null && !r.error().isBlank()) {
                sb.append(",\"error\":\"").append(escapeJson(r.error())).append('"');
            }
            sb.append('}');
        }
        sb.append("]}");
        post("/api/rootmc/economy/transfers/complete", sb.toString());
    }

    /**
     * Queue a Claims-host treasury grant for a Votifier payout (idempotent transfer id).
     * @return true when the Worker accepted/queued the credit
     */
    public boolean queueClaimsVoteCredit(
            String minecraftUuid,
            String minecraftUsername,
            String service,
            double amount,
            String votedAtIso) throws IOException, InterruptedException {
        if (minecraftUuid == null || minecraftUuid.isBlank() || amount < 0.01d) {
            return false;
        }
        String body = "{"
                + "\"minecraft_uuid\":\"" + escapeJson(minecraftUuid) + "\","
                + "\"minecraft_username\":\"" + escapeJson(minecraftUsername == null ? "" : minecraftUsername) + "\","
                + "\"service\":\"" + escapeJson(service == null ? "default" : service) + "\","
                + "\"amount\":" + amount + ","
                + "\"voted_at\":\"" + escapeJson(votedAtIso == null || votedAtIso.isBlank()
                        ? Instant.now().toString()
                        : votedAtIso) + "\""
                + "}";
        String json = post("/api/rootmc/votes/claims-credit", body);
        return json != null && json.contains("\"ok\":true");
    }

    public record ClaimsVoteBackfillResult(int scanned, int queued) {}

    public ClaimsVoteBackfillResult triggerClaimsVoteBackfill()
            throws IOException, InterruptedException {
        String json = post("/api/rootmc/votes/claims-backfill", "{}");
        int scanned = (int) Math.round(parseJsonNumber(json, "scanned"));
        int queued = (int) Math.round(parseJsonNumber(json, "queued"));
        return new ClaimsVoteBackfillResult(scanned, queued);
    }

    private static String buildIngameEventsBody(
            List<com.rootrecord.minecraft.rootmc.ingame.IngameEventBuffer.PendingEvent> events) {
        StringBuilder sb = new StringBuilder("{\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            var e = events.get(i);
            sb.append("{\"minecraft_uuid\":\"").append(escapeJson(e.minecraftUuid())).append('"');
            if (e.minecraftUsername() != null) {
                sb.append(",\"minecraft_username\":\"").append(escapeJson(e.minecraftUsername())).append('"');
            }
            sb.append(",\"event_type\":\"").append(escapeJson(e.eventType())).append('"');
            sb.append(",\"world_name\":\"").append(escapeJson(e.worldName())).append('"');
            sb.append(",\"dimension\":\"").append(escapeJson(e.dimension())).append('"');
            sb.append(",\"x\":").append(e.x());
            sb.append(",\"y\":").append(e.y());
            sb.append(",\"z\":").append(e.z());
            if (e.label() != null) {
                sb.append(",\"label\":\"").append(escapeJson(e.label())).append('"');
            }
            if (e.body() != null) {
                sb.append(",\"body\":\"").append(escapeJson(e.body())).append('"');
            }
            sb.append(",\"created_at\":\"").append(escapeJson(e.createdAt())).append('"');
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public record VaultClaimResult(List<VaultItem> items) {
        public record VaultItem(String itemKey, int quantity, String voucherId, String voucherTier) {}

        static VaultClaimResult parse(String json) {
            List<VaultItem> items = new ArrayList<>();
            int idx = 0;
            while (true) {
                int keyStart = json.indexOf("\"item_key\"", idx);
                if (keyStart < 0) {
                    break;
                }
                String slice = json.substring(keyStart, Math.min(json.length(), keyStart + 280));
                String itemKey = extractString(slice, "item_key");
                String qtyStr = extractString(slice, "quantity");
                String voucherId = extractString(slice, "voucher_id");
                String voucherTier = extractString(slice, "voucher_tier");
                if (itemKey != null && qtyStr != null) {
                    try {
                        items.add(new VaultItem(
                                itemKey,
                                Integer.parseInt(qtyStr),
                                voucherId,
                                voucherTier));
                    } catch (NumberFormatException ignored) {
                        /* skip malformed */
                    }
                }
                idx = keyStart + 12;
            }
            return new VaultClaimResult(items);
        }
    }

    public int syncServerStats(List<ServerPlayerSnapshot> players) throws IOException, InterruptedException {
        if (players == null || players.isEmpty()) {
            return 0;
        }
        String syncedAt = Instant.now().toString();
        int total = 0;
        final int batchSize = 40;
        for (int i = 0; i < players.size(); i += batchSize) {
            int end = Math.min(players.size(), i + batchSize);
            String body = buildServerSyncBody(players.subList(i, end), syncedAt);
            post("/api/realm/minecraft/mcmmo/sync", body);
            total += end - i;
        }
        return total;
    }

    /** @deprecated use {@link #syncServerStats} */
    public int syncMcmmo(List<McMMOPlayerSnapshot> players) throws IOException, InterruptedException {
        if (players == null || players.isEmpty()) {
            return 0;
        }
        List<ServerPlayerSnapshot> mapped = new ArrayList<>();
        for (McMMOPlayerSnapshot p : players) {
            mapped.add(new ServerPlayerSnapshot(
                    p.uuid(), p.username(), p.powerLevel(), p.skills(), null, null, null));
        }
        return syncServerStats(mapped);
    }

    private static String buildEconomySyncBody(EconomySnapshot snapshot, String syncedAt) {
        StringBuilder sb = new StringBuilder("{\"synced_at\":\"")
                .append(escapeJson(syncedAt))
                .append("\",\"shop_prices\":[");
        for (int i = 0; i < snapshot.shopPrices().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            EconomySnapshot.ShopPriceRow row = snapshot.shopPrices().get(i);
            sb.append("{\"item_key\":\"").append(escapeJson(row.itemKey())).append("\",\"prices\":[");
            for (int p = 0; p < row.prices().size(); p++) {
                if (p > 0) {
                    sb.append(',');
                }
                sb.append(row.prices().get(p));
            }
            sb.append("],\"source\":\"").append(escapeJson(row.source())).append("\"}");
        }
        sb.append("],\"shop_listings\":[");
        for (int i = 0; i < snapshot.shopListings().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            EconomySnapshot.ShopListingRow row = snapshot.shopListings().get(i);
            sb.append("{\"shop_id\":\"").append(escapeJson(row.shopId())).append('"');
            if (row.ownerUuid() != null && !row.ownerUuid().isBlank()) {
                sb.append(",\"owner_uuid\":\"").append(escapeJson(row.ownerUuid())).append('"');
            }
            if (row.ownerUsername() != null && !row.ownerUsername().isBlank()) {
                sb.append(",\"owner_username\":\"").append(escapeJson(row.ownerUsername())).append('"');
            }
            sb.append(",\"world\":\"").append(escapeJson(row.worldName())).append('"');
            sb.append(",\"x\":").append(row.x());
            sb.append(",\"y\":").append(row.y());
            sb.append(",\"z\":").append(row.z());
            sb.append(",\"item_key\":\"").append(escapeJson(row.itemKey())).append('"');
            sb.append(",\"price\":").append(row.price());
            sb.append(",\"listing_type\":\"").append(escapeJson(row.listingType())).append("\"");
            sb.append(",\"stock_quantity\":").append(Math.max(0, row.stockQuantity()));
            sb.append("}");
        }
        sb.append("],\"balances\":[");
        for (int i = 0; i < snapshot.balances().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            EconomySnapshot.BalanceRow row = snapshot.balances().get(i);
            sb.append("{\"minecraft_uuid\":\"").append(escapeJson(row.uuid())).append('"');
            if (row.username() != null && !row.username().isBlank()) {
                sb.append(",\"minecraft_username\":\"").append(escapeJson(row.username())).append('"');
            }
            sb.append(",\"balance\":").append(row.balance());
            sb.append(",\"currency\":\"").append(escapeJson(row.currency())).append("\"}");
        }
        sb.append("],\"system_balances\":[");
        for (int i = 0; i < snapshot.systemBalances().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            EconomySnapshot.SystemBalanceRow row = snapshot.systemBalances().get(i);
            sb.append("{\"minecraft_uuid\":\"").append(escapeJson(row.uuid())).append('"');
            if (row.username() != null && !row.username().isBlank()) {
                sb.append(",\"minecraft_username\":\"").append(escapeJson(row.username())).append('"');
            }
            sb.append(",\"balance\":").append(row.balance());
            sb.append(",\"account_type\":\"").append(escapeJson(row.accountType())).append('"');
            sb.append(",\"currency\":\"").append(escapeJson(row.currency())).append("\"}");
        }
        sb.append("],\"player_items\":[");
        for (int i = 0; i < snapshot.playerItems().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            EconomySnapshot.PlayerItemsRow row = snapshot.playerItems().get(i);
            sb.append("{\"minecraft_uuid\":\"").append(escapeJson(row.uuid())).append('"');
            if (row.username() != null && !row.username().isBlank()) {
                sb.append(",\"minecraft_username\":\"").append(escapeJson(row.username())).append('"');
            }
            sb.append(",\"source\":\"").append(escapeJson(row.source())).append("\",\"items\":{");
            int itemIdx = 0;
            for (var entry : row.items().entrySet()) {
                if (itemIdx++ > 0) {
                    sb.append(',');
                }
                sb.append('"').append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
            }
            sb.append("}}");
        }
        sb.append("],\"server_items\":{");
        int serverIdx = 0;
        for (var entry : snapshot.serverItems().entrySet()) {
            if (serverIdx++ > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
        }
        sb.append("},\"treasury_balance\":").append(snapshot.treasuryBalance());
        sb.append(",\"treasury_ledger\":[");
        for (int i = 0; i < snapshot.treasuryLedger().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            EconomySnapshot.TreasuryLedgerRow row = snapshot.treasuryLedger().get(i);
            sb.append("{\"mysql_id\":").append(row.mysqlId());
            sb.append(",\"entry_type\":\"").append(escapeJson(row.entryType())).append('"');
            sb.append(",\"amount\":").append(row.amount());
            if (row.fromUuid() != null && !row.fromUuid().isBlank()) {
                sb.append(",\"from_uuid\":\"").append(escapeJson(row.fromUuid())).append('"');
            }
            if (row.toUuid() != null && !row.toUuid().isBlank()) {
                sb.append(",\"to_uuid\":\"").append(escapeJson(row.toUuid())).append('"');
            }
            if (row.details() != null && !row.details().isBlank()) {
                sb.append(",\"details\":\"").append(escapeJson(row.details())).append('"');
            }
            if (row.createdAt() != null && !row.createdAt().isBlank()) {
                sb.append(",\"created_at\":\"").append(escapeJson(row.createdAt())).append('"');
            }
            sb.append('}');
        }
        sb.append("],\"playtime_monthly\":[");
        for (int i = 0; i < snapshot.playtimeMonthly().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            EconomySnapshot.PlaytimeMonthlyRow row = snapshot.playtimeMonthly().get(i);
            sb.append("{\"minecraft_uuid\":\"").append(escapeJson(row.uuid())).append('"');
            sb.append(",\"month_key\":\"").append(escapeJson(row.monthKey())).append('"');
            sb.append(",\"playtime_seconds\":").append(row.playtimeSeconds());
            sb.append('}');
        }
        sb.append("],\"town_tax_rates\":[");
        for (int i = 0; i < snapshot.townTaxRates().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            EconomySnapshot.TownTaxRow row = snapshot.townTaxRates().get(i);
            sb.append("{\"town_name\":\"").append(escapeJson(row.townName())).append('"');
            if (row.mayorName() != null && !row.mayorName().isBlank()) {
                sb.append(",\"mayor_name\":\"").append(escapeJson(row.mayorName())).append('"');
            }
            sb.append(",\"tax_percent\":").append(row.taxPercent());
            sb.append('}');
        }
        sb.append("],\"gold_found\":[");
        for (int i = 0; i < snapshot.goldFound().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            EconomySnapshot.GoldFoundRow row = snapshot.goldFound().get(i);
            sb.append("{\"minecraft_uuid\":\"").append(escapeJson(row.uuid())).append('"');
            if (row.username() != null && !row.username().isBlank()) {
                sb.append(",\"minecraft_username\":\"").append(escapeJson(row.username())).append('"');
            }
            sb.append(",\"total_gold_g\":").append(row.totalGoldG());
            sb.append(",\"mined_ore_g\":").append(row.minedOreG());
            sb.append(",\"mined_block_g\":").append(row.minedBlockG());
            sb.append(",\"loot_chest_g\":").append(row.lootChestG());
            sb.append(",\"loot_mob_g\":").append(row.lootMobG());
            sb.append(",\"pickup_g\":").append(row.pickupG());
            sb.append(",\"find_events\":").append(row.findEvents());
            sb.append('}');
        }
        sb.append("],\"gold_item_events\":[");
        for (int i = 0; i < snapshot.goldItemEvents().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            EconomySnapshot.GoldItemEventRow row = snapshot.goldItemEvents().get(i);
            sb.append("{\"event_id\":").append(row.eventId());
            sb.append(",\"minecraft_uuid\":\"").append(escapeJson(row.uuid())).append('"');
            if (row.username() != null && !row.username().isBlank()) {
                sb.append(",\"minecraft_username\":\"").append(escapeJson(row.username())).append('"');
            }
            sb.append(",\"event_type\":\"").append(escapeJson(row.eventType())).append('"');
            sb.append(",\"obtained_via\":\"").append(escapeJson(row.obtainedVia())).append('"');
            sb.append(",\"material\":\"").append(escapeJson(row.material())).append('"');
            sb.append(",\"stack_amount\":").append(row.stackAmount());
            sb.append(",\"gold_g\":").append(row.goldG());
            if (row.world() != null && !row.world().isBlank()) {
                sb.append(",\"world\":\"").append(escapeJson(row.world())).append('"');
            }
            if (row.blockX() != null) {
                sb.append(",\"block_x\":").append(row.blockX());
            }
            if (row.blockY() != null) {
                sb.append(",\"block_y\":").append(row.blockY());
            }
            if (row.blockZ() != null) {
                sb.append(",\"block_z\":").append(row.blockZ());
            }
            if (row.contextJson() != null && !row.contextJson().isBlank()) {
                sb.append(",\"context_json\":\"").append(escapeJson(row.contextJson())).append('"');
            }
            sb.append(",\"occurred_at\":\"").append(escapeJson(row.createdAt())).append('"');
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    public record DividendPayout(String id, String minecraftUuid, String minecraftUsername, double amount, String monthKey) {}

    public record DividendPayoutResult(String id, String status, String error) {}

    public List<DividendPayout> fetchPendingDividendPayouts() throws IOException, InterruptedException {
        String json = get("/api/rootmc/treasury/dividends/pending");
        List<DividendPayout> out = new ArrayList<>();
        int idx = 0;
        while (idx < json.length()) {
            int idPos = json.indexOf("\"id\"", idx);
            if (idPos < 0) {
                break;
            }
            String chunk = json.substring(idPos, Math.min(json.length(), idPos + 520));
            String id = extractString(chunk, "id");
            if (id == null || id.isBlank()) {
                break;
            }
            String uuid = extractString(chunk, "minecraft_uuid");
            String username = extractString(chunk, "minecraft_username");
            String monthKey = extractString(chunk, "month_key");
            double amount = parseJsonNumber(chunk, "amount");
            if (uuid != null && amount >= 0.01d) {
                out.add(new DividendPayout(id, uuid, username, amount, monthKey));
            }
            idx = idPos + 4;
            if (out.size() >= 100) {
                break;
            }
        }
        return out;
    }

    public void completeDividendPayouts(List<DividendPayoutResult> results)
            throws IOException, InterruptedException {
        if (results == null || results.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("{\"payouts\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            DividendPayoutResult r = results.get(i);
            sb.append("{\"id\":\"").append(escapeJson(r.id())).append('"');
            sb.append(",\"status\":\"").append(escapeJson(r.status())).append('"');
            if (r.error() != null && !r.error().isBlank()) {
                sb.append(",\"error_message\":\"").append(escapeJson(r.error())).append('"');
            }
            sb.append('}');
        }
        sb.append("]}");
        post("/api/rootmc/treasury/dividends/complete", sb.toString());
    }

    private static String buildShopListingBody(
            EconomySnapshot.ShopListingRow row,
            boolean delete,
            String shopId,
            String itemKey,
            String previousItemKey,
            String syncedAt) {
        StringBuilder sb = new StringBuilder("{\"synced_at\":\"").append(escapeJson(syncedAt)).append('"');
        sb.append(",\"action\":\"").append(delete ? "delete" : "upsert").append('"');
        if (delete) {
            if (shopId != null && !shopId.isBlank()) {
                sb.append(",\"shop_id\":\"").append(escapeJson(shopId)).append('"');
            }
            if (itemKey != null && !itemKey.isBlank()) {
                sb.append(",\"item_key\":\"").append(escapeJson(itemKey)).append('"');
            }
        } else if (row != null) {
            sb.append(",\"listing\":").append(shopListingJson(row));
        }
        if (previousItemKey != null && !previousItemKey.isBlank()) {
            sb.append(",\"previous_item_key\":\"").append(escapeJson(previousItemKey)).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String shopListingJson(EconomySnapshot.ShopListingRow row) {
        StringBuilder sb = new StringBuilder("{\"shop_id\":\"").append(escapeJson(row.shopId())).append('"');
        if (row.ownerUuid() != null && !row.ownerUuid().isBlank()) {
            sb.append(",\"owner_uuid\":\"").append(escapeJson(row.ownerUuid())).append('"');
        }
        if (row.ownerUsername() != null && !row.ownerUsername().isBlank()) {
            sb.append(",\"owner_username\":\"").append(escapeJson(row.ownerUsername())).append('"');
        }
        sb.append(",\"world\":\"").append(escapeJson(row.worldName())).append('"');
        sb.append(",\"x\":").append(row.x());
        sb.append(",\"y\":").append(row.y());
        sb.append(",\"z\":").append(row.z());
        sb.append(",\"item_key\":\"").append(escapeJson(row.itemKey())).append('"');
        sb.append(",\"price\":").append(row.price());
        sb.append(",\"listing_type\":\"").append(escapeJson(row.listingType())).append('"');
        sb.append(",\"stock_quantity\":").append(Math.max(0, row.stockQuantity()));
        sb.append('}');
        return sb.toString();
    }

    private static String buildServerSyncBody(List<ServerPlayerSnapshot> players, String syncedAt) {
        StringBuilder sb = new StringBuilder("{\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ServerPlayerSnapshot p = players.get(i);
            sb.append("{\"minecraft_uuid\":\"").append(escapeJson(p.uuid())).append('"');
            if (p.username() != null && !p.username().isBlank()) {
                sb.append(",\"minecraft_username\":\"").append(escapeJson(p.username())).append('"');
            }
            if (p.powerLevel() != null) {
                sb.append(",\"power_level\":").append(p.powerLevel());
            }
            sb.append(",\"synced_at\":\"").append(escapeJson(syncedAt)).append('"');
            if (p.skills() != null && !p.skills().isEmpty()) {
                sb.append(",\"skills\":{");
                int skillIdx = 0;
                for (var entry : p.skills().entrySet()) {
                    if (skillIdx++ > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
                }
                sb.append('}');
            }
            if (p.playtimeSeconds() != null) {
                sb.append(",\"playtime_seconds\":").append(p.playtimeSeconds());
            }
            if (p.firstJoinAt() != null && !p.firstJoinAt().isBlank()) {
                sb.append(",\"first_join_at\":\"").append(escapeJson(p.firstJoinAt())).append('"');
            }
            if (p.lastLoginAt() != null && !p.lastLoginAt().isBlank()) {
                sb.append(",\"last_login_at\":\"").append(escapeJson(p.lastLoginAt())).append('"');
            }
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String buildMcmmoSyncBody(List<McMMOPlayerSnapshot> players, String syncedAt) {
        StringBuilder sb = new StringBuilder("{\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            McMMOPlayerSnapshot p = players.get(i);
            sb.append("{\"minecraft_uuid\":\"").append(escapeJson(p.uuid())).append('"');
            if (p.username() != null && !p.username().isBlank()) {
                sb.append(",\"minecraft_username\":\"").append(escapeJson(p.username())).append('"');
            }
            sb.append(",\"power_level\":").append(p.powerLevel());
            sb.append(",\"synced_at\":\"").append(escapeJson(syncedAt)).append('"');
            sb.append(",\"skills\":{");
            int skillIdx = 0;
            for (var entry : p.skills().entrySet()) {
                if (skillIdx++ > 0) {
                    sb.append(',');
                }
                sb.append('"').append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
            }
            sb.append("}}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String get(String path) throws IOException, InterruptedException {
        return exchange("GET", path, null, true);
    }

    private String publicGet(String path) throws IOException, InterruptedException {
        return exchange("GET", path, null, false);
    }

    public record ItemValueQuote(
            String itemKey, double each, double perStack, int stackSize, int samples) {}

    public List<ItemValueQuote> lookupItemValue(String itemQuery) throws IOException, InterruptedException {
        String encoded = java.net.URLEncoder.encode(itemQuery, java.nio.charset.StandardCharsets.UTF_8);
        String server = java.net.URLEncoder.encode(config.serverId(), java.nio.charset.StandardCharsets.UTF_8);
        String json = publicGet("/api/rootmc/server/value?item=" + encoded + "&server_id=" + server);
        List<ItemValueQuote> out = new ArrayList<>();
        int idx = 0;
        while (idx < json.length()) {
            int keyPos = json.indexOf("\"item_key\"", idx);
            if (keyPos < 0) {
                break;
            }
            String itemKey = extractString(json.substring(keyPos), "item_key");
            if (itemKey == null || itemKey.isBlank()) {
                break;
            }
            String chunk = json.substring(keyPos, Math.min(json.length(), keyPos + 420));
            double each = parseJsonNumber(chunk, "price_per_each");
            double stack = parseJsonNumber(chunk, "price_per_stack");
            int stackSize = (int) parseJsonNumber(chunk, "stack_size");
            int samples = (int) parseJsonNumber(chunk, "sample_count");
            if (stackSize <= 0) {
                stackSize = 64;
            }
            out.add(new ItemValueQuote(itemKey, each, stack, stackSize, samples));
            idx = keyPos + 10;
            if (out.size() >= 4) {
                break;
            }
        }
        return out;
    }

    private static double parseJsonNumber(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return 0;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String post(String path, String jsonBody) throws IOException, InterruptedException {
        return exchange("POST", path, jsonBody, true);
    }

    private String exchange(String method, String path, String jsonBody, boolean authorized)
            throws IOException, InterruptedException {
        String configured = com.rootrecord.minecraft.common.config.RootMcApiBases.normalize(config.apiBase());
        String preferred = com.rootrecord.minecraft.common.config.RootMcApiBases.preferredBase(configured);
        try {
            return exchangeOnce(preferred, method, path, jsonBody, authorized);
        } catch (IOException first) {
            String alt = com.rootrecord.minecraft.common.config.RootMcApiBases.fallbackBase(preferred);
            if (alt == null || alt.equalsIgnoreCase(preferred)) {
                throw first;
            }
            boolean retry = com.rootrecord.minecraft.common.config.RootMcApiBases.looksLikeThrottleMessage(
                            first.getMessage())
                    || com.rootrecord.minecraft.common.config.RootMcApiBases.looksLikeEdgeDownMessage(
                            first.getMessage());
            if (!retry) {
                throw first;
            }
            return exchangeOnce(alt, method, path, jsonBody, authorized);
        }
    }

    private String exchangeOnce(
            String base, String method, String path, String jsonBody, boolean authorized)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(30));
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        } else {
            builder.GET();
        }
        HttpRequest request = authorized ? authorized(builder) : builder.build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String body = response.body() == null ? "" : response.body();
            throw new IOException("HTTP " + response.statusCode() + ": " + body);
        }
        return response.body();
    }

    private HttpRequest authorized(HttpRequest.Builder builder) {
        return builder
                .header("X-RootStat-Server-Id", config.serverId())
                .header("X-RootStat-Server-Secret", config.serverSecret())
                .build();
    }

    private static String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\"([^\"]*)\"|null)");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        return m.group(2);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record LinkStatus(
            boolean linked,
            boolean discordLinked,
            String accountId,
            String email,
            String minecraftUsername,
            String verifiedAt,
            String discordUserId,
            boolean proUnlocked,
            boolean lifeMember,
            boolean topActivePlayer) {
        static LinkStatus unlinked() {
            return new LinkStatus(false, false, null, null, null, null, null, false, false, false);
        }

        /** Player-facing label — never show raw account UUID. */
        public String displayLabel() {
            if (email != null && !email.isBlank()) {
                return email;
            }
            if (minecraftUsername != null && !minecraftUsername.isBlank()) {
                return minecraftUsername;
            }
            return "your RootMC account";
        }
    }

    public record GovernanceVotingPower(
            boolean ok,
            boolean eligible,
            double sharePercent,
            String summary,
            String votingChannelUrl,
            String constitutionUrl) {}
}
