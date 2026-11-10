package com.familychat.app.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 服务端「密码加密密钥备份」查询结果的三态语义（纯 JVM，无 android.* 依赖）。
 *
 * ─────────────────────────────────────────────────────────────
 * 为什么必须单独有这么一个类（H2 的核心）
 * ─────────────────────────────────────────────────────────────
 * 旧实现是 `ApiClient.fetchKeyBackup()` 返回 `String`/`null`，把三种完全不同的情况
 * 揉成了一个 `null`：
 *   ① 服务端**确认没有**备份   → 可以放心上传（新用户/首次备份）
 *   ② 网络/HTTP/解析**失败**   → **绝不能上传**（无法确认现状，上传可能毁掉唯一可恢复的备份）
 *   ③ 有备份
 * 后果：`KeyResetActivity` 里"拉取失败就跳过密码预检"的分支被静默走通，
 * 用户拿着错密码也会覆盖掉旧备份，而 UI 还报「已同步更新」。
 *
 * 因此本类把语义固定成三态，并且**失败只能是异常、不能是返回值**：
 *   · {@link #parse(int, String)} 成功返回非 null 状态（exists 告诉你 ①/③）；
 *   · 任何 HTTP 非 2xx / 响应不是合法 JSON / 字段类型不对 → 抛 {@link ParseException}。
 * 调用方因此无法再"忘记处理失败"：不写 catch 就编译不过（checked exception）。
 *
 * 纯 JVM 的意义：这段逻辑可以在没有模拟器、没有网络的情况下被单测直接覆盖
 * （见 `KeyBackupResponseTest`）。真机/真实网络那一段（OkHttp → 异常）由
 * `ApiClient.fetchKeyBackupState()` 承担，属必须真机手测的部分，见 docs/guides/RELEASE.md §7.3。
 *
 * ⚠️ 服务端契约：`KeyBackupHandler.getKeyBackup` 在无备份时返回
 *    `{"backup_b64": ""}`（空串，而不是缺字段）。两种写法这里都按"无备份"处理，
 *    但 e2e 脚本 `NB-1` 钉死的是"空串"这一种。
 */
public final class KeyBackupResponse {

    /** 服务端无备份时的约定值（空串）。 */
    public static final String NO_BACKUP = "";

    /** 查询成功后的状态。 */
    public static final class State {
        /** 服务端是否确实存有备份。 */
        public final boolean exists;
        /** 备份密文（Base64）；{@code exists == false} 时为 null。 */
        public final String backupB64;

        State(boolean exists, String backupB64) {
            this.exists = exists;
            this.backupB64 = backupB64;
        }

        /** 语义糖：服务端确认没有备份（可以安全上传）。 */
        public boolean isEmpty() {
            return !exists;
        }
    }

    /** 无法确认备份状态（HTTP 失败 / 响应格式非法）。调用方**必须**据此拒绝上传。 */
    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }

        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private KeyBackupResponse() {
    }

    /**
     * 把「HTTP 状态码 + 响应体」解析成三态。
     *
     * @param statusCode HTTP 状态码（OkHttp 已保证 2xx 才走到这里；非 2xx 直接抛）
     * @param body       响应体原文
     * @return 非 null 的 {@link State}
     * @throws ParseException 非 2xx、响应体不是 JSON 对象、或 backup_b64 非字符串
     */
    public static State parse(int statusCode, String body) throws ParseException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new ParseException("服务器返回错误状态: " + statusCode);
        }
        if (body == null || body.trim().isEmpty()) {
            throw new ParseException("服务器返回空响应，无法确认备份状态");
        }

        JsonObject json;
        try {
            com.google.gson.JsonElement el = JsonParser.parseString(body);
            if (el == null || !el.isJsonObject()) {
                throw new ParseException("备份状态响应不是 JSON 对象");
            }
            json = el.getAsJsonObject();
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("备份状态响应解析失败: " + e.getClass().getSimpleName(), e);
        }

        if (!json.has("backup_b64") || json.get("backup_b64").isJsonNull()) {
            // 字段缺失：按服务端契约视为"无备份"。
            // 注意这不等于"拉取失败"——HTTP 已经成功了，只是这个账号还没备份过。
            return new State(false, null);
        }

        String b;
        try {
            b = json.get("backup_b64").getAsString();
        } catch (Exception e) {
            throw new ParseException("backup_b64 不是字符串", e);
        }

        if (b == null || b.isEmpty()) {
            // 服务端明确回答"没有备份"（约定为空串）
            return new State(false, null);
        }
        return new State(true, b);
    }
}
