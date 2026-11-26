package io.github.samzhu.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@BackendIntegrationTest
class PostgresCommentsIntegrationTests {
    @Autowired JdbcTemplate db;

    @Test
    void runCommentDescribesTheWholeLifecycleRatherThanOneProviderCall() {
        assertEquals("一次使用者請求從發起至完成、失敗或取消的完整 Run 生命週期；可包含多筆 ProviderAttempt。",
                db.queryForObject("select obj_description('chat_run'::regclass, 'pg_class')", String.class));
    }

    @Test
    void migratedSchemaDocumentsApplicationTables() {
        String applicationTables = """
                from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname=current_schema() and c.relkind in ('r','p')
                and c.relname not in ('databasechangelog','databasechangeloglock','schema_version')
                """;
        assertEquals(33, db.queryForObject("select count(*) " + applicationTables, Integer.class));
        assertEquals(List.of(), db.queryForList("select c.relname " + applicationTables
                + " and obj_description(c.oid, 'pg_class') is null order by c.relname", String.class));
        assertEquals("使用者與 Bot 的持續對話主題及歷史；不同於模型推論記憶脈絡 Session。", tableComment(db, "conversation"));
        assertEquals("一次 ProviderAttempt，記錄模型供應商呼叫的嘗試與歸屬；可在派送前終止，一個 Run 可有多筆。", tableComment(db, "provider_attempt"));
        assertEquals("聊天嘗試所屬 Run；背景命名等工作可無 Run。", columnComment(db, "provider_attempt", "run_id"));
        assertEquals("嘗試狀態：IN_PROGRESS、SUCCEEDED、FAILED、CANCELLED、UNKNOWN 或 NOT_DISPATCHED；與用量完整性分開。", columnComment(db, "provider_attempt", "execution_outcome"));
        assertEquals("以 PostgreSQL 時間判定的租約期限，過期只允許競爭接管，不代表程序已停止。", columnComment(db, "run_ownership", "lease_until"));
        assertEquals("各用量項目每百萬計量單位的 USD 十進位字串費率；計量單位依價格項目而定。", columnComment(db, "pricing_catalog_entry", "rates"));
        assertEquals("供應商原生用量 JSON；各欄位的計量單位與意義依來源而定，例如 token 數。", columnComment(db, "usage_snapshot", "raw_usage"));
        assertEquals("Spring AI 2.1 Session JDBC 核心狀態表，儲存模型續聊之脈絡工作區。", tableComment(db, "ai_session"));
        assertEquals("Session 識別碼（主鍵，1:1 對齊 conversation_id）。", columnComment(db, "ai_session", "id"));
    }

    private static String tableComment(JdbcTemplate db, String table) {
        return db.queryForObject("select obj_description(?::regclass, 'pg_class')", String.class, table);
    }

    private static String columnComment(JdbcTemplate db, String table, String column) {
        return db.queryForObject("""
                select col_description(?::regclass, attnum) from pg_attribute
                where attrelid=?::regclass and attname=? and not attisdropped
                """, String.class, table, table, column);
    }
}
