package com.familychat.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import okhttp3.Dns;

import org.junit.Test;

/**
 * 服务器地址 / 协议判定的纯逻辑测试（无需模拟器）。
 *
 * 这段逻辑决定了"用 http 还是 https""用 ws 还是 wss"：
 * 判错会导致局域网连不上（明文被网络策略拦截）或公网握手失败。
 */
public class ServerProtocolTest {

    // ===== 局域网：必须走 http/ws =====

    @Test
    public void privateIpv4_isNotSecure() {
        String[] lanHosts = {
                "192.168.0.1",
                "192.168.1.1",
                "192.168.0.255",
                "10.0.0.1",
                "10.255.255.254",
                "172.16.0.1",
                "172.20.10.5",
                "172.31.255.255",
                "127.0.0.1",
                "localhost",
                "0.0.0.0",
        };
        for (String host : lanHosts) {
            assertFalse(host + " 是局域网地址，应判定为非加密(http/ws)",
                    ServerProtocol.isSecure(host));
        }
    }

    @Test
    public void lanWithPort_isNotSecure() {
        assertFalse(ServerProtocol.isSecure("192.168.0.1:5000"));
        assertFalse(ServerProtocol.isSecure("10.0.0.5:8080"));
        assertFalse(ServerProtocol.isSecure("localhost:5000"));
    }

    // ===== 公网：必须走 https/wss =====

    @Test
    public void publicHost_isSecure() {
        String[] wanHosts = {
                "chat.example.com",
                "download.example.com",
                "abc-def.trycloudflare.com",
                "8.8.8.8",
                "172.15.0.1",   // 172.15 不在私有段内
                "172.32.0.1",   // 172.32 不在私有段内
                "192.169.1.1",  // 不是 192.168
                "11.0.0.1",     // 不是 10.
        };
        for (String host : wanHosts) {
            assertTrue(host + " 是公网地址，应判定为加密(https/wss)",
                    ServerProtocol.isSecure(host));
        }
    }

    @Test
    public void ipv6Loopback_isNotTreatedAsSecure() {
        // 当前实现按 IPv4 前缀判断，IPv6 回环不属于私有 IPv4 段。
        // 此用例锁定现状，防止无声行为变化（若将来支持 IPv6 需同步更新）。
        assertEquals(false, ServerProtocol.isSecure("127.0.0.1"));
    }

    @Test
    public void nullAndEmpty_areNotSecure() {
        assertFalse("null 不应判为加密（否则会拿 https 去连局域网）",
                ServerProtocol.isSecure(null));
        assertFalse("空串不应判为加密", ServerProtocol.isSecure(""));
        assertFalse("空白串不应判为加密", ServerProtocol.isSecure("   "));
    }

    // ===== 协议前缀拼接 =====

    @Test
    public void httpOf_matchesLanOrWan() {
        assertEquals("http://", ServerProtocol.httpOf("192.168.0.1:5000"));
        assertEquals("https://", ServerProtocol.httpOf("chat.example.com"));
        assertEquals("http://", ServerProtocol.httpOf("localhost:5000"));
        assertEquals("https://", ServerProtocol.httpOf("8.8.8.8:5000"));
    }

    @Test
    public void wsOf_matchesLanOrWan() {
        assertEquals("ws://", ServerProtocol.wsOf("192.168.0.1:5000"));
        assertEquals("wss://", ServerProtocol.wsOf("chat.example.com"));
        assertEquals("ws://", ServerProtocol.wsOf("10.0.0.2:5000"));
    }

    // ===== 媒体 URL 追加 token（Glide/ExoPlayer 无法带 Authorization 头）=====

    @Test
    public void withToken_appendsUsingCorrectSeparator() {
        assertEquals("http://h/a.png?token=t",
                ServerProtocol.withToken("http://h/a.png", "t"));
        assertEquals("http://h/a.png?x=1&token=t",
                ServerProtocol.withToken("http://h/a.png?x=1", "t"));
    }

    @Test
    public void withToken_isIdempotent() {
        // 已有 token 时不能再追加（否则 URL 会越来越长且参数重复）
        assertEquals("http://h/a.png?token=t",
                ServerProtocol.withToken("http://h/a.png?token=t", "t2"));
        assertEquals("http://h/a.png?x=1&token=t",
                ServerProtocol.withToken("http://h/a.png?x=1&token=t", "t2"));
    }

    @Test
    public void withToken_passesThroughWhenNothingToDo() {
        assertEquals("http://h/a.png", ServerProtocol.withToken("http://h/a.png", null));
        assertEquals("http://h/a.png", ServerProtocol.withToken("http://h/a.png", ""));
        assertEquals(null, ServerProtocol.withToken(null, "t"));
    }

    // ===== 假域名 DNS：每次解析都现取当前地址 =====

    @Test
    public void fakeHostDns_followsLaterServerAddressChanges() throws Exception {
        // 同一个 Dns 实例：服务器 IP 变了（DHCP 换网段 / 用户在设置里改地址）之后
        // 必须解析到新 IP。回归背景：Glide 每进程只注册一次 OkHttpUrlLoader，它手里那份
        // client 快照永远换不掉 —— 一旦把 IP 固化进闭包，地址一改所有头像/图片就被解析到
        // 旧 IP，而接口（走动态取 client 的 ApiClient）却完全正常。
        final String[] server = {"192.168.1.119:5000"};
        Dns dns = ServerProtocol.fakeHostDns(() -> server[0]);

        assertEquals("192.168.1.119",
                dns.lookup(ServerProtocol.FAKE_HOST).get(0).getHostAddress());

        server[0] = "192.168.1.120:5000";
        assertEquals("192.168.1.120",
                dns.lookup(ServerProtocol.FAKE_HOST).get(0).getHostAddress());
    }

    @Test
    public void fakeHostDns_onlyHijacksFakeHost() throws Exception {
        Dns dns = ServerProtocol.fakeHostDns(() -> "192.168.1.120:5000");
        // 其余主机名原样交给系统解析器（这里用 IP 字面量断言，测试不依赖网络/DNS）
        assertEquals("127.0.0.1", dns.lookup("127.0.0.1").get(0).getHostAddress());
        // 假域名匹配不区分大小写（App 内部拼的是小写，但解析器不该挑大小写）
        assertEquals("192.168.1.120", dns.lookup("MiYu.LAN").get(0).getHostAddress());
    }
}
