package com.familychat.server.ws;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约测试：服务端通话信令白名单必须与客户端发送的信令**恰好**对齐。
 *
 * <h3>为什么必须有这条测试</h3>
 * {@code WsHandler.CALL_SIGNALING_TYPES} 漏一条的后果是<b>静默失效</b>：
 * 客户端 {@code PushConnection.sendCallBusy/Reject/Cancel} 发得出去，服务端不匹配白名单
 * 就什么都不做 —— 表现为"对方忙 / 已拒绝 / 已取消"永远不生效，没有任何异常、没有任何日志。
 * 这与客户端 {@code ListenerFanoutContractTest} 守的是同一类问题（漏覆写 = 事件静默丢失），
 * 两侧各一道防线。
 *
 * <p>本测试断言集合<b>恰好</b>是这 11 条：多一条（误匹配别的报文类型）或少一条都会红。
 * 将来新增 {@code call_hold} 之类，必须同时改本测试 —— 这是刻意的摩擦。
 */
class WsHandlerContractTest {

    /** 客户端 :core WebSocketManager 目前会发出的全部通话信令。 */
    private static final Set<String> EXPECTED = new HashSet<>(Arrays.asList(
            "call_offer", "call_answer", "call_ice", "call_hangup",
            "call_busy", "call_reject", "call_cancel",
            "call_media", "call_video", "call_video_state", "call_media_stop"));

    /**
     * 触碰 {@code WsHandler} 之前必须先备好 JWT 密钥文件。
     *
     * <p>{@code WsHandler} 的静态初始化会构造 {@code JWTVerifier}
     * （{@code ALGO = Algorithm.HMAC256(ServerConfig.getJwtSecret())}），
     * 而 {@code ServerConfig} 在「数据库已存在 + 密钥文件缺失」时会 <b>拒绝启动</b>
     * （防误轮换的硬保护，见 ServerConfig.loadOrGenerateSecret）。
     *
     * <p>测试数据目录 {@code build/test-data} 里恰恰只有 {@code DbTestSupport} 建出来的
     * {@code family_chat.db}，没有 {@code jwt_secret.key} —— 若某个 DB 测试先跑过，本类
     * 就会撞上那个组合。所以这里先补一个测试专用密钥。
     * 内容固定、且只写在 gradle 管理的 build/ 目录下，与真实 {@code data/} 完全无关。
     * 长度 ≥ 32 字节，满足 java-jwt 对 HS256 密钥强度的要求。
     */
    @BeforeAll
    static void ensureJwtSecretExists() throws Exception {
        Path dir = Paths.get(System.getProperty("familychat.data.dir", "build/test-data"));
        Files.createDirectories(dir);
        Path key = dir.resolve("jwt_secret.key");
        if (!Files.exists(key)) {
            Files.writeString(key, "test-only-jwt-secret-do-not-use-in-production");
        }
    }

    @Test
    void whitelistIsExactlyTheTenCallSignals() {
        assertEquals(new TreeSet<>(EXPECTED), new TreeSet<>(WsHandler.CALL_SIGNALING_TYPES),
                "通话信令白名单必须恰好是这 11 条。少一条 = 该信令被服务端静默丢弃"
                        + "（客户端发得出、对端永远收不到、无异常无日志）；"
                        + "多一条 = 可能误匹配其它报文类型。");
        assertEquals(11, WsHandler.CALL_SIGNALING_TYPES.size(),
                "白名单条数必须恰好为 11");
    }

    @Test
    void isCallSignalingAcceptsWhitelistAndRejectsEverythingElse() {
        for (String t : EXPECTED) {
            assertTrue(WsHandler.isCallSignaling(t), t + " 应被识别为通话信令");
        }
        for (String t : new String[]{null, "", "call_", "call_busyy", "CALL_OFFER",
                "call_offer ", "private_message", "new_message", "group_new_message"}) {
            assertFalse(WsHandler.isCallSignaling(t),
                    "'" + t + "' 不应被识别为通话信令（null 也要安全返回 false）");
        }
    }

    @Test
    void whitelistDoesNotAccidentallyIncludeMessageTypes() {
        assertFalse(WsHandler.CALL_SIGNALING_TYPES.contains("private_message"),
                "私聊消息走 handlePrivateMessage，不能混进通话白名单");
        assertFalse(WsHandler.CALL_SIGNALING_TYPES.contains("group_new_message"),
                "群消息不能混进通话白名单");
    }
}
