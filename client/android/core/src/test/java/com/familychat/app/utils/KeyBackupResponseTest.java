package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * 「服务端备份查询」三态语义的纯 JVM 单测（H2 回归保护）。
 *
 * 守的是什么：**「服务端确认没有备份」与「没能问到」绝不能混为一谈**。
 * 混掉的后果不是"多一次重试"，而是：用户用错密码也会覆盖掉唯一可恢复的旧备份，
 * 而界面还报「已同步更新」。所以下面的负例比正例更重要 ——
 * 每一种"问不到"的情形都必须抛异常，而不是返回一个看起来正常的 false。
 *
 * 覆盖不到的部分（诚实说明）：真实 HTTP 那一段（OkHttp 抛 IOException →
 * `ApiClient.fetchKeyBackupState()` 转成 ApiException）无法在纯 JVM 里覆盖，
 * 因为本机离线缓存中没有 mockwebserver（见 docs/guides/RELEASE.md §7.3）。
 * **该分支只能真机手测**（飞行模式下重置密钥 → 必须拒绝上传）。
 */
public class KeyBackupResponseTest {

    // ===== 正例：服务端确实有备份 =====

    @Test
    public void parse_reportsExistsWithCiphertext() throws Exception {
        String cipher = "Ag8QERITFBUWFxgZGhscHR4fICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj9A";
        String body = "{\"backup_b64\":\"" + cipher + "\"}";
        KeyBackupResponse.State s = KeyBackupResponse.parse(200, body);
        assertTrue("应报告存在备份", s.exists);
        assertFalse("exists=true 时不应被判为 empty", s.isEmpty());
        assertEquals("密文必须逐字符一致（不得做任何 trim/规范化）", cipher, s.backupB64);
    }

    @Test
    public void parse_keepsLongCiphertextIntact() throws Exception {
        // 真实备份密文约 300+ 字符，含 base64 的 '+' '/' '=' 与末尾填充，
        // 解析层绝不能改动它（否则解密端会解不开）。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".charAt(i % 64));
        }
        sb.append("==");
        String cipher = sb.toString();
        KeyBackupResponse.State s = KeyBackupResponse.parse(200, "{\"backup_b64\":\"" + cipher + "\"}");
        assertEquals(cipher.length(), s.backupB64.length());
        assertEquals(cipher, s.backupB64);
    }

    // ===== 正例：服务端明确回答"没有备份" =====

    @Test
    public void parse_emptyStringMeansNoBackup() throws Exception {
        KeyBackupResponse.State s = KeyBackupResponse.parse(200, "{\"backup_b64\":\"\"}");
        assertFalse("空串 = 服务端确认无备份", s.exists);
        assertTrue(s.isEmpty());
        assertEquals(null, s.backupB64);
    }

    @Test
    public void parse_missingFieldMeansNoBackup() throws Exception {
        // 字段缺失也算"没有备份"：HTTP 已经成功，只是这个账号从没备份过。
        // ⚠️ 这与"拉取失败"是两件事，后者必须抛异常（见下面的负例）。
        KeyBackupResponse.State s = KeyBackupResponse.parse(200, "{}");
        assertFalse(s.exists);
        assertEquals(null, s.backupB64);
    }

    @Test
    public void parse_jsonNullFieldMeansNoBackup() throws Exception {
        KeyBackupResponse.State s = KeyBackupResponse.parse(200, "{\"backup_b64\":null}");
        assertFalse(s.exists);
        assertEquals(null, s.backupB64);
    }

    // ===== 负例：所有"问不到"的情形都必须抛异常 =====

    @Test
    public void parse_rejectsNon2xx() {
        assertThrows(500, "{\"backup_b64\":\"x\"}", "500");
        assertThrows(502, "", "502");
        assertThrows(404, "{}", "404");
        assertThrows(401, "{\"error\":\"未登录\"}", "401");
        assertThrows(302, "", "302");
    }

    @Test
    public void parse_rejectsMalformedBody() {
        assertThrows(200, "", "空响应");
        assertThrows(200, "   ", "空白响应");
        assertThrows(200, "<html>502 Bad Gateway</html>", "HTML 错误页");
        assertThrows(200, "not-json", "非 JSON");
        assertThrows(200, "[1,2,3]", "JSON 数组而非对象");
        assertThrows(200, "{\"backup_b64\":{\"a\":1}}", "字段类型不对");
    }

    /**
     * 这是本类最重要的一条语义：**失败绝不能退化成"看起来像没有备份"**。
     *
     * 如果哪天有人把 parse 改成"出错就返回 exists=false"，这条会立刻红：
     * 那样 `KeyResetActivity` 会认为服务端空着、于是用错密码照传新备份，
     * 把唯一可恢复的旧备份顶掉，用户下次找回时才发现解不开。
     */
    @Test
    public void failureNeverLooksLikeNoBackup() {
        int[] badStatus = {500, 502, 503, 401, 403, 404, 400};
        for (int code : badStatus) {
            try {
                KeyBackupResponse.State s = KeyBackupResponse.parse(code, "{\"backup_b64\":\"\"}");
                fail("HTTP " + code + " 必须抛异常，却返回了 exists=" + s.exists);
            } catch (KeyBackupResponse.ParseException expected) {
                // 正确：调用方必须显式处理"问不到"
            }
        }
    }

    private static void assertThrows(int status, String body, String what) {
        try {
            KeyBackupResponse.State s = KeyBackupResponse.parse(status, body);
            fail("应抛异常(" + what + ")，却返回 exists=" + s.exists + " b64=" + s.backupB64);
        } catch (KeyBackupResponse.ParseException expected) {
            assertTrue("异常信息应非空", expected.getMessage() != null
                    && !expected.getMessage().isEmpty());
        }
    }
}
