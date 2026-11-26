package io.github.samzhu.bot.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samzhu.bot.BackendIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@BackendIntegrationTest
class ConversationPinningIntegrationTests {
    private static final String CALLER = "dev-user";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String prefix = "pin-" + UUID.randomUUID() + "-";
    private ConversationTestData data;
    private String personalConversationId;

    @BeforeEach
    void setUp() {
        data = new ConversationTestData(jdbcTemplate);
    }

    @AfterEach
    void cleanUp() {
        try {
            if (personalConversationId != null) {
                // The unscoped mutation uses the shared caller's personal workspace.
                jdbcTemplate.update("DELETE FROM conversation WHERE id = ? AND user_id = ?",
                        personalConversationId, CALLER);
            }
            data.close();
        } finally {
            httpClient.close();
        }
    }

    private JsonNode executeGraphQL(String payload) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        return objectMapper.readTree(resp.body());
    }

    private JsonNode createConversation(String title, String workspaceId) throws Exception {
        Map<String, Object> variables = new HashMap<>();
        variables.put("title", title);
        if (workspaceId != null) {
            variables.put("workspaceId", workspaceId);
        }
        String payload = objectMapper.writeValueAsString(Map.of(
                "query", """
                        mutation CreateConv($title: String, $workspaceId: ID) {
                          createConversation(botId: "bot-general", title: $title, workspaceId: $workspaceId) {
                            id workspaceId pinned title
                          }
                        }
                        """,
                "variables", variables));
        JsonNode root = executeGraphQL(payload);
        assertFalse(root.has("errors"), root.toString());
        JsonNode conversation = root.path("data").path("createConversation");
        assertFalse(conversation.path("id").asText().isBlank(), root.toString());
        return conversation;
    }

    private JsonNode conversations(String workspaceId, Boolean pinned, int limit, int offset) throws Exception {
        Map<String, Object> variables = new HashMap<>(Map.of(
                "workspaceId", workspaceId, "limit", limit, "offset", offset));
        if (pinned != null) {
            variables.put("pinned", pinned);
        }
        JsonNode root = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", """
                        query List($workspaceId: ID!, $pinned: Boolean, $limit: Int!, $offset: Int!) {
                          conversations(workspaceId: $workspaceId, pinned: $pinned, limit: $limit, offset: $offset) {
                            id pinned
                          }
                        }
                        """, "variables", variables)));
        assertFalse(root.has("errors"), root.toString());
        JsonNode page = root.path("data").path("conversations");
        assertTrue(page.isArray(), root.toString());
        return page;
    }

    @Test
    @DisplayName("1. Create conversation defaults to pinned=false and queries expose pinned: Boolean!")
    void testCreateAndQueryDefaultPinnedFalse() throws Exception {
        String title = prefix + "Default Pinned Test";
        JsonNode created = createConversation(title, null);
        String convId = created.path("id").asText();
        personalConversationId = convId;
        String personalWorkspaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM workspace WHERE personal_owner_id = ?", String.class, CALLER);
        assertEquals(personalWorkspaceId, created.path("workspaceId").asText());
        assertEquals(Boolean.FALSE, jdbcTemplate.queryForObject(
                "SELECT pinned FROM conversation WHERE id = ? AND user_id = ? AND workspace_id = ?",
                Boolean.class, convId, CALLER, personalWorkspaceId));

        String queryPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "query", "query GetConv($id: ID!) { conversation(id: $id) { id title pinned } }",
                "variables", java.util.Map.of("id", convId)
        ));

        JsonNode root = executeGraphQL(queryPayload);
        JsonNode convNode = root.path("data").path("conversation");
        assertFalse(root.has("errors"), root.toString());
        assertEquals(convId, convNode.path("id").asText());
        assertTrue(convNode.path("pinned").isBoolean());
        assertFalse(convNode.path("pinned").asBoolean(), "Newly created conversation must default to pinned=false");

        String otherWorkspaceId = data.workspace(CALLER);
        data.insertConversation(prefix + "other-workspace", "bot-general", title, CALLER, false,
                Instant.now().plusSeconds(60), otherWorkspaceId);
        JsonNode list = executeGraphQL(objectMapper.writeValueAsString(Map.of(
                "query", "query List($title: String!) { conversations(search: $title, limit: 1) { id } }",
                "variables", Map.of("title", title))));
        assertFalse(list.has("errors"), list.toString());
        JsonNode page = list.path("data").path("conversations");
        assertEquals(1, page.size());
        assertEquals(convId, page.get(0).path("id").asText());
    }

    @Test
    @DisplayName("2. setConversationPinned toggles pinned state true and false with JSON roundtrip")
    void testSetAndUnsetPinningJsonRoundtrip() throws Exception {
        String workspaceId = data.workspace(CALLER);
        JsonNode created = createConversation("Toggle Pin Test", workspaceId);
        String convId = created.path("id").asText();
        assertEquals(workspaceId, created.path("workspaceId").asText());

        // Pin conversation
        String pinPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "query", "mutation Pin($id: ID!) { setConversationPinned(id: $id, pinned: true) { id pinned } }",
                "variables", java.util.Map.of("id", convId)
        ));
        JsonNode pinRoot = executeGraphQL(pinPayload);
        JsonNode pinData = pinRoot.path("data").path("setConversationPinned");
        assertFalse(pinData.isMissingNode(), "Pin response should be present: " + pinRoot);
        assertTrue(pinData.path("pinned").asBoolean(), "Pinned should now be true");

        // Verify via reload query
        String getPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "query", "query GetConv($id: ID!) { conversation(id: $id) { id pinned } }",
                "variables", java.util.Map.of("id", convId)
        ));
        JsonNode getRoot = executeGraphQL(getPayload);
        assertTrue(getRoot.path("data").path("conversation").path("pinned").asBoolean());

        // Unpin conversation
        String unpinPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "query", "mutation Unpin($id: ID!) { setConversationPinned(id: $id, pinned: false) { id pinned } }",
                "variables", java.util.Map.of("id", convId)
        ));
        JsonNode unpinRoot = executeGraphQL(unpinPayload);
        JsonNode unpinData = unpinRoot.path("data").path("setConversationPinned");
        assertEquals(convId, unpinData.path("id").asText(), unpinRoot.toString());
        assertTrue(unpinData.path("pinned").isBoolean());
        assertFalse(unpinData.path("pinned").asBoolean(), "Pinned should now be false");
    }

    @Test
    @DisplayName("3. Owner validation rejects unauthorized updates to conversations owned by other users via pure SQL fixture")
    void testOwnerForeignUserNegativeCase() throws Exception {
        String workspaceId = data.workspace(CALLER);
        String foreign = "foreign-" + UUID.randomUUID();
        data.addMember(workspaceId, foreign);
        String foreignConvId = prefix + "foreign";
        data.insertConversation(foreignConvId, "bot-general", "Foreign Owned Conversation",
                foreign, false, Instant.now(), workspaceId);

        // Caller via public GraphQL is dev-user (default CurrentUserProvider)
        String attackPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "query", "mutation Pin($id: ID!) { setConversationPinned(id: $id, pinned: true) { id pinned } }",
                "variables", java.util.Map.of("id", foreignConvId)
        ));
        JsonNode root = executeGraphQL(attackPayload);

        JsonNode errors = root.path("errors");
        assertFalse(errors.isMissingNode(), "Unauthorized request must produce GraphQL errors: " + root);
        assertTrue(errors.size() > 0, "Errors array must not be empty");

        JsonNode firstError = errors.get(0);
        String classification = firstError.path("extensions").path("classification").asText();
        assertEquals("FORBIDDEN", classification, "Classification must be FORBIDDEN: " + firstError);

        String errorMsg = firstError.path("message").asText();
        assertEquals("Conversation access denied", errorMsg);
        assertEquals("CONVERSATION_ACCESS_DENIED", firstError.path("extensions").path("code").asText());
        assertFalse(errorMsg.contains(foreign));
        assertFalse(errorMsg.contains(foreignConvId));

        String missingPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "query", "mutation Pin($id: ID!) { setConversationPinned(id: $id, pinned: true) { id pinned } }",
                "variables", java.util.Map.of("id", "missing-" + UUID.randomUUID())
        ));
        JsonNode missingError = executeGraphQL(missingPayload).path("errors").get(0);
        assertEquals(errorMsg, missingError.path("message").asText());
        assertEquals(firstError.path("extensions").path("code"), missingError.path("extensions").path("code"));
        assertEquals(firstError.path("extensions").path("classification"), missingError.path("extensions").path("classification"));

        // Verify database state was not changed
        Boolean pinnedInDb = jdbcTemplate.queryForObject(
                "SELECT pinned FROM conversation WHERE id = ?",
                Boolean.class, foreignConvId);
        assertNotNull(pinnedInDb);
        assertFalse(pinnedInDb, "Database state must remain unchanged after rejected unauthorized request");
    }

    @Test
    @DisplayName("4. Idempotence & zero side-effects: toggling pin before/after strictly preserves updated_at, runs, messages and session")
    void testIdempotentPinningPreservesUpdatedAtAndRunSession() throws Exception {
        String convId = "conv-idemp-" + UUID.randomUUID();
        String runId = "run-idemp-" + UUID.randomUUID();
        Instant t0 = Instant.now().minusSeconds(120);

        String workspaceId = data.workspace(CALLER);
        data.insertConversation(convId, "bot-general", "Idempotency Non-Empty Test",
                CALLER, false, t0, workspaceId);

        jdbcTemplate.update("""
            INSERT INTO chat_run (id, conversation_id, client_request_id, input_text, status, error_message, created_at, updated_at)
            VALUES (?, ?, 'req-idemp-1', 'Initial User Input', 'COMPLETED', NULL, ?, ?)
        """, runId, convId, Timestamp.from(t0), Timestamp.from(t0));

        jdbcTemplate.update("""
            INSERT INTO message (id, conversation_id, run_id, role, content, interrupted, created_at)
            VALUES (?, ?, ?, 'USER', 'Initial User Input', false, ?),
                   (?, ?, ?, 'ASSISTANT', 'Initial Assistant Response', false, ?)
        """, UUID.randomUUID().toString(), convId, runId, Timestamp.from(t0),
             UUID.randomUUID().toString(), convId, runId, Timestamp.from(t0));

        // Insert session record
        jdbcTemplate.update("""
            INSERT INTO ai_session (id, created_at, metadata, event_version)
            VALUES (?, ?, '{"principalName":"dev-user"}', 1)
            ON CONFLICT (id) DO NOTHING
        """, convId, Timestamp.from(t0));

        // Snapshot initial state BEFORE any pin toggle
        String originalUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at::text FROM conversation WHERE id = ?", String.class, convId);
        Integer originalRunCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_run WHERE conversation_id = ?", Integer.class, convId);
        Integer originalMsgCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM message WHERE conversation_id = ?", Integer.class, convId);
        Integer originalSessionCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_session WHERE id = ?", Integer.class, convId);

        assertEquals(1, originalRunCount);
        assertEquals(2, originalMsgCount);
        assertEquals(1, originalSessionCount);

        // Sleep 50ms to ensure clock advances
        Thread.sleep(50);

        // Step A: First toggle (false -> true)
        String pinPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "query", "mutation Pin($id: ID!) { setConversationPinned(id: $id, pinned: true) { id pinned } }",
                "variables", java.util.Map.of("id", convId)
        ));
        JsonNode firstRoot = executeGraphQL(pinPayload);
        assertTrue(firstRoot.path("data").path("setConversationPinned").path("pinned").asBoolean());

        String afterToggleUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at::text FROM conversation WHERE id = ?", String.class, convId);
        assertEquals(originalUpdatedAt, afterToggleUpdatedAt,
                "Toggling pinned status must strictly preserve conversation updated_at");
        assertEquals(originalRunCount, jdbcTemplate.queryForObject("SELECT count(*) FROM chat_run WHERE conversation_id = ?", Integer.class, convId));
        assertEquals(originalMsgCount, jdbcTemplate.queryForObject("SELECT count(*) FROM message WHERE conversation_id = ?", Integer.class, convId));
        assertEquals(originalSessionCount, jdbcTemplate.queryForObject("SELECT count(*) FROM ai_session WHERE id = ?", Integer.class, convId));

        // Sleep 50ms
        Thread.sleep(50);

        // Step B: Idempotent repeat (true -> true)
        JsonNode repeatRoot = executeGraphQL(pinPayload);
        assertTrue(repeatRoot.path("data").path("setConversationPinned").path("pinned").asBoolean());

        String afterRepeatUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at::text FROM conversation WHERE id = ?", String.class, convId);
        assertEquals(originalUpdatedAt, afterRepeatUpdatedAt,
                "Re-applying same pinned status must be idempotent and strictly preserve updated_at");
        assertEquals(originalRunCount, jdbcTemplate.queryForObject("SELECT count(*) FROM chat_run WHERE conversation_id = ?", Integer.class, convId));
        assertEquals(originalMsgCount, jdbcTemplate.queryForObject("SELECT count(*) FROM message WHERE conversation_id = ?", Integer.class, convId));
        assertEquals(originalSessionCount, jdbcTemplate.queryForObject("SELECT count(*) FROM ai_session WHERE id = ?", Integer.class, convId));

        // Step C: Toggle unpin (true -> false)
        String unpinPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "query", "mutation Unpin($id: ID!) { setConversationPinned(id: $id, pinned: false) { id pinned } }",
                "variables", java.util.Map.of("id", convId)
        ));
        JsonNode unpinRoot = executeGraphQL(unpinPayload);
        JsonNode unpinned = unpinRoot.path("data").path("setConversationPinned");
        assertEquals(convId, unpinned.path("id").asText(), unpinRoot.toString());
        assertTrue(unpinned.path("pinned").isBoolean());
        assertFalse(unpinned.path("pinned").asBoolean());

        String afterUnpinUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at::text FROM conversation WHERE id = ?", String.class, convId);
        assertEquals(originalUpdatedAt, afterUnpinUpdatedAt,
                "Unpinning must strictly preserve updated_at without side-effects");
        assertEquals(originalRunCount, jdbcTemplate.queryForObject("SELECT count(*) FROM chat_run WHERE conversation_id = ?", Integer.class, convId));
        assertEquals(originalMsgCount, jdbcTemplate.queryForObject("SELECT count(*) FROM message WHERE conversation_id = ?", Integer.class, convId));
        assertEquals(originalSessionCount, jdbcTemplate.queryForObject("SELECT count(*) FROM ai_session WHERE id = ?", Integer.class, convId));
    }

    @Test
    @DisplayName("5. conversations pagination, pinned filter (null, true, false), stable tie-breaker and cap 100 on >100 dataset")
    void testPaginationWithPinnedFilterAndCap100() throws Exception {
        String workspaceId = data.workspace(CALLER);
        Instant baseTime = Instant.now().minusSeconds(10000);
        for (int i = 0; i < 55; i++) {
            String suffix = String.format("%03d", i);
            data.insertConversation(prefix + "p-" + suffix, "bot-general", "Pinned " + i,
                    CALLER, true, baseTime.plusSeconds(i * 10L), workspaceId);
            data.insertConversation(prefix + "u-" + suffix, "bot-general", "Unpinned " + i,
                    CALLER, false, baseTime.plusSeconds(i * 10L + 5), workspaceId);
        }

        JsonNode capList = conversations(workspaceId, null, 200, 0);
        assertEquals(100, capList.size(), "Querying limit 200 on dataset of 110 items must be strictly capped at 100");

        JsonNode pinnedList = conversations(workspaceId, true, 100, 0);
        assertEquals(55, pinnedList.size(), "Should return all 55 pinned conversations");
        for (JsonNode item : pinnedList) {
            assertTrue(item.path("pinned").asBoolean());
        }
        assertEquals(prefix + "p-054", pinnedList.get(0).path("id").asText());
        assertEquals(prefix + "p-000", pinnedList.get(54).path("id").asText());

        JsonNode unpinnedList = conversations(workspaceId, false, 100, 0);
        assertEquals(55, unpinnedList.size(), "Should return all 55 unpinned conversations");
        for (JsonNode item : unpinnedList) {
            assertFalse(item.path("pinned").asBoolean());
        }
        assertEquals(prefix + "u-054", unpinnedList.get(0).path("id").asText());
        assertEquals(prefix + "u-000", unpinnedList.get(54).path("id").asText());

        JsonNode p1List = conversations(workspaceId, true, 20, 0);
        JsonNode p2List = conversations(workspaceId, true, 20, 20);
        JsonNode p3List = conversations(workspaceId, true, 20, 40);
        assertEquals(20, p1List.size());
        assertEquals(20, p2List.size());
        assertEquals(15, p3List.size());

        // Disjointness check: Page 1 and Page 2 must not overlap
        Set<String> p1Ids = new HashSet<>();
        p1List.forEach(node -> p1Ids.add(node.path("id").asText()));
        for (JsonNode node : p2List) {
            assertFalse(p1Ids.contains(node.path("id").asText()), "Page 2 must not overlap with Page 1");
        }

        // E. Stable Tie-Breaker: identical updated_at timestamps order strictly by id DESC
        Instant tieTime = Instant.now().plusSeconds(1000);
        for (int index : List.of(1, 3, 2)) {
            data.insertConversation(prefix + "tie-" + index, "bot-general", "Tie " + index,
                    CALLER, true, tieTime, workspaceId);
        }

        JsonNode tieList = conversations(workspaceId, true, 3, 0);
        assertEquals(3, tieList.size());
        assertEquals(prefix + "tie-3", tieList.get(0).path("id").asText());
        assertEquals(prefix + "tie-2", tieList.get(1).path("id").asText());
        assertEquals(prefix + "tie-1", tieList.get(2).path("id").asText());
    }

    @Test
    @DisplayName("6. Database catalog metadata verification (column definition, column comment content, indexes)")
    void testDatabaseCatalogMetadata() {
        // Verify column definition
        Integer columnCount = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM information_schema.columns
            WHERE table_name = 'conversation' AND column_name = 'pinned'
              AND data_type = 'boolean' AND is_nullable = 'NO'
        """, Integer.class);
        assertEquals(1, columnCount, "Column conversation.pinned must exist as boolean NOT NULL");

        // Verify column comment content
        String columnComment = jdbcTemplate.queryForObject("""
            SELECT col_description('conversation'::regclass, (
                SELECT attnum FROM pg_attribute
                WHERE attrelid = 'conversation'::regclass AND attname = 'pinned'
            ))
        """, String.class);
        assertNotNull(columnComment, "Column comment on conversation.pinned must be present");
        assertEquals("對話釘選狀態，true 表示已釘選至側欄頂部，false 為預設未釘選。", columnComment.trim(),
                "Column comment must match migration specification exactly");

        // Verify composite index
        Integer indexCount = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM pg_indexes
            WHERE tablename = 'conversation' AND indexname = 'idx_conv_user_pinned_updated'
        """, Integer.class);
        assertEquals(1, indexCount, "Composite index idx_conv_user_pinned_updated must exist");

        // Verify backward-compatible unfiltered index still exists
        Integer oldIndexCount = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM pg_indexes
            WHERE tablename = 'conversation' AND indexname = 'idx_conv_user_updated'
        """, Integer.class);
        assertEquals(1, oldIndexCount, "Existing index idx_conv_user_updated must still exist");
    }

    @Test
    @DisplayName("7. Issue #12: Deep dual-section pagination (>=125 pinned, >=125 unpinned), limit 100 cap, range slicing, cross-section pin mutation, zero side-effects")
    void testIssue12DualSectionDeepPaginationAndReconciliation() throws Exception {
        String initialWorkspaceId = data.workspace(CALLER);
        String initialConversationId = prefix + "initial";
        data.insertConversation(initialConversationId, "bot-general", "Initial Target Test",
                CALLER, false, Instant.now().minusSeconds(100000), initialWorkspaceId);

        JsonNode emptyPinnedList = conversations(initialWorkspaceId, true, 20, 0);
        assertEquals(0, emptyPinnedList.size(), "Target pinned section must initially be empty");

        String pinMutation = objectMapper.writeValueAsString(Map.of(
                "query", "mutation Pin($id: ID!) { setConversationPinned(id: $id, pinned: true) { id pinned } }",
                "variables", Map.of("id", initialConversationId)));
        JsonNode pinResult = executeGraphQL(pinMutation).path("data").path("setConversationPinned");
        assertTrue(pinResult.path("pinned").asBoolean());

        JsonNode movedPinnedList = conversations(initialWorkspaceId, true, 20, 0);
        assertEquals(1, movedPinnedList.size(), "Target section should now contain exactly 1 conversation");
        assertEquals(initialConversationId, movedPinnedList.get(0).path("id").asText(),
                "Moved item must be the first entry in target section");

        String workspaceId = data.workspace(CALLER);
        // Phase 2: Full Deep Dataset Setup (Exactly 125 pinned and 125 unpinned)
        final int COUNT = 125;
        List<Object[]> batchArgs = new ArrayList<>();

        Instant baseTime = Instant.now().minusSeconds(50000);

        // Generate 125 pinned and 125 unpinned items with strictly managed timestamps and ties
        for (int i = 0; i < COUNT; i++) {
            // Include a timestamp tie between item 10 and 11
            long secondOffset = (i == 11) ? (10 * 10) : (i * 10);
            Instant time = baseTime.plusSeconds(secondOffset);

            String pId = prefix + String.format("pin-%03d", i);
            batchArgs.add(new Object[]{pId, "bot-general", "Pinned Conv " + i, CALLER, true,
                    Timestamp.from(time), Timestamp.from(time), workspaceId});

            String uId = prefix + String.format("unp-%03d", i);
            batchArgs.add(new Object[]{uId, "bot-general", "Unpinned Conv " + i, CALLER, false,
                    Timestamp.from(time), Timestamp.from(time), workspaceId});
        }

        jdbcTemplate.batchUpdate(ConversationTestData.INSERT_CONVERSATION, batchArgs);

        // Query database catalog directly to build authoritative expected order matching (updated_at DESC, id DESC)
        List<String> authoritativePinned = jdbcTemplate.query(
            "SELECT id FROM conversation WHERE workspace_id = ? AND user_id = ? AND pinned = true ORDER BY updated_at DESC, id DESC",
            (rs, rowNum) -> rs.getString("id"), workspaceId, CALLER
        );
        assertEquals(COUNT, authoritativePinned.size());

        List<String> authoritativeUnpinned = jdbcTemplate.query(
            "SELECT id FROM conversation WHERE workspace_id = ? AND user_id = ? AND pinned = false ORDER BY updated_at DESC, id DESC",
            (rs, rowNum) -> rs.getString("id"), workspaceId, CALLER
        );
        assertEquals(COUNT, authoritativeUnpinned.size());

        // Verify tie breaker in authoritative list: item 11 and item 10 have identical timestamp, so conv-pin-011 precedes conv-pin-010 by id DESC
        int idx11 = authoritativePinned.indexOf(prefix + "pin-011");
        int idx10 = authoritativePinned.indexOf(prefix + "pin-010");
        assertTrue(idx11 < idx10, "With identical updated_at, conv-pin-011 must precede conv-pin-010 by id DESC tie-breaker");

        // Phase 3: Limit 100 Cap & Offset 100 Slicing on both pinned and unpinned
        for (boolean pinnedFlag : List.of(true, false)) {
            List<String> authList = pinnedFlag ? authoritativePinned : authoritativeUnpinned;

            // 1. Limit 200 requested -> strictly capped at 100
            JsonNode capList = conversations(workspaceId, pinnedFlag, 200, 0);
            assertEquals(100, capList.size(), "limit 200 must be strictly capped at 100 items");
            for (int j = 0; j < 100; j++) {
                assertEquals(authList.get(j), capList.get(j).path("id").asText(), "Item " + j + " must match authoritative order");
                assertEquals(pinnedFlag, capList.get(j).path("pinned").asBoolean());
            }

            // 2. Limit 100 with offset 100 -> returns exactly the remaining 25 items (100 to 124)
            JsonNode remainingList = conversations(workspaceId, pinnedFlag, 100, 100);
            assertEquals(25, remainingList.size(), "offset 100 on 125 items must return exactly 25 remaining items");
            for (int j = 0; j < 25; j++) {
                assertEquals(authList.get(100 + j), remainingList.get(j).path("id").asText(), "Remaining item " + j + " must match authoritative order");
                assertEquals(pinnedFlag, remainingList.get(j).path("pinned").asBoolean());
            }

            // 3. Offset beyond total (offset 125 or 140) -> returns empty page []
            JsonNode emptyList = conversations(workspaceId, pinnedFlag, 20, 125);
            assertEquals(0, emptyList.size(), "offset 125 on 125 items must return empty list []");

            JsonNode emptyList2 = conversations(workspaceId, pinnedFlag, 20, 140);
            assertEquals(0, emptyList2.size(), "offset 140 must return empty list []");

            // Phase 4: Full Multi-page Traversal with limit=20 (7 pages: 20*6 + 5 = 125)
            List<String> accumulatedIds = new ArrayList<>();
            for (int pageIdx = 0; pageIdx < 7; pageIdx++) {
                int offset = pageIdx * 20;
                JsonNode pageList = conversations(workspaceId, pinnedFlag, 20, offset);
                int expectedPageSize = (pageIdx == 6) ? 5 : 20;
                assertEquals(expectedPageSize, pageList.size(), "Page " + pageIdx + " size mismatch");

                for (JsonNode node : pageList) {
                    accumulatedIds.add(node.path("id").asText());
                }
            }

            // Verify no duplicates and no holes across the entire 125-item sequence
            assertEquals(COUNT, accumulatedIds.size(), "Total accumulated items across pages must be exactly 125");
            Set<String> uniqueIds = new HashSet<>(accumulatedIds);
            assertEquals(COUNT, uniqueIds.size(), "There must be ZERO duplicate IDs across paginated pages");
            assertEquals(authList, accumulatedIds, "Accumulated paginated IDs must strictly match authoritative order");
        }

        // Phase 5: Cross-section Pin Toggle & Range Reconciliation Integrity
        // Pick conv-unp-050 from unpinned and pin it
        String targetConvId = prefix + "unp-050";
        Timestamp updatedAtBefore = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM conversation WHERE id = ?", Timestamp.class, targetConvId);

        String togglePinPayload = String.format(
            "{\"query\":\"mutation { setConversationPinned(id: \\\"%s\\\", pinned: true) { id pinned updatedAt } }\"}", targetConvId);
        JsonNode toggleResult = executeGraphQL(togglePinPayload).path("data").path("setConversationPinned");
        assertTrue(toggleResult.path("pinned").asBoolean(), "Target conversation must now be pinned");

        // Verify updated_at is STRICTLY PRESERVED in the database
        Timestamp updatedAtAfter = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM conversation WHERE id = ?", Timestamp.class, targetConvId);
        assertEquals(updatedAtBefore, updatedAtAfter, "Toggling pin must NOT modify updated_at timestamp");

        // Verify unpinned count is now 124 (100 in first batch + 24 in second batch)
        JsonNode unpBatch1 = conversations(workspaceId, false, 100, 0);
        JsonNode unpBatch2 = conversations(workspaceId, false, 100, 100);
        assertEquals(100, unpBatch1.size());
        assertEquals(24, unpBatch2.size());
        int totalUnpinned = unpBatch1.size() + unpBatch2.size();
        assertEquals(124, totalUnpinned, "Unpinned list total count must now be exactly 124");

        // Verify targetConvId is NO LONGER present in unpinned section
        for (JsonNode n : unpBatch1) assertNotEquals(targetConvId, n.path("id").asText());
        for (JsonNode n : unpBatch2) assertNotEquals(targetConvId, n.path("id").asText());

        // Verify pinned count is now 126 (100 in first batch + 26 in second batch)
        JsonNode pinBatch1 = conversations(workspaceId, true, 100, 0);
        JsonNode pinBatch2 = conversations(workspaceId, true, 100, 100);
        assertEquals(100, pinBatch1.size());
        assertEquals(26, pinBatch2.size());
        int totalPinned = pinBatch1.size() + pinBatch2.size();
        assertEquals(126, totalPinned, "Pinned list total count must now be exactly 126");

        // Verify authoritative ordering in new pinned section matches updated_at DESC, id DESC
        List<String> combinedPinnedIds = new ArrayList<>();
        pinBatch1.forEach(n -> combinedPinnedIds.add(n.path("id").asText()));
        pinBatch2.forEach(n -> combinedPinnedIds.add(n.path("id").asText()));
        assertTrue(combinedPinnedIds.contains(targetConvId), "Newly pinned conversation must be present in pinned section");

        List<String> newAuthPinned = jdbcTemplate.query(
            "SELECT id FROM conversation WHERE workspace_id = ? AND user_id = ? AND pinned = true ORDER BY updated_at DESC, id DESC",
            (rs, rowNum) -> rs.getString("id"), workspaceId, CALLER
        );
        assertEquals(newAuthPinned, combinedPinnedIds, "Pinned section after toggle must strictly match authoritative database ordering");
    }
}
