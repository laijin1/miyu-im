package com.familychat.server;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单聊媒体 IDOR 防护所依赖的「文件名 → 上传者 userId」解析约定。
 * 解析失败必须返回 -1，让 FileHandler 走 404 fail-closed（绝不默认放行）。
 */
class FileAccessTest extends DbTestSupport {

    private static int invoke(String filename) throws Exception {
        Method m = Class.forName("com.familychat.server.handler.FileHandler")
                .getDeclaredMethod("uploaderIdOf", String.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, filename);
    }

    @Test
    void resolvesCurrentNamingConvention() throws Exception {
        // {毫秒时间戳}_{8位uuid}_{userId}.{ext} —— uploadFile 生成的格式
        assertEquals(4, invoke("1784893283013_f88c2fb1_4.mp4"));
        assertEquals(13, invoke("1784843675666_a1b2c3d4_13.jpg"));
        assertEquals(102, invoke("1784893283013_00000000_102.bin"));
    }

    @Test
    void resolvesLegacyNamingConvention() throws Exception {
        // 早期格式 {时间戳}_{userId}.{ext}（无 uuid 段）—— 生产目录里真实存在
        assertEquals(4, invoke("1784843675666_4.webm"));
        assertEquals(10, invoke("1784875724719_10.bin"));
        assertEquals(2, invoke("1784847022126_2.webm"));
    }

    @Test
    void failsClosedWhenUploaderCannotBeDetermined() throws Exception {
        // 非服务端命名（测试夹具/手放文件）→ -1，调用方必须 404
        assertEquals(-1, invoke("video_test_123.mp4"));
        assertEquals(-1, invoke("thumb_test_123.jpg"));
        assertEquals(-1, invoke("test_clip.mp4"));
        assertEquals(-1, invoke(".."));
        assertEquals(-1, invoke(""));
        assertEquals(-1, invoke(null));
    }
}
