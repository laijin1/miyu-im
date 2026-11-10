package com.familychat.app.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Gson 序列化契约测试 —— 本项目最昂贵的一次故障的回归防线。
 *
 * 背景（真实事故）：向服务端发送的请求体字段名没有 @SerializedName，
 * R8 混淆把 username 改成了 a，服务端按 "username" 读取失败，
 * Javalin 抛 BadRequestResponse → **空响应体 400**，客户端只显示"服务器错误: 400"。
 * 排查花了很久，因为 debug 包不混淆，问题只在 release 包出现。
 *
 * 因此这里做两件事：
 *   1. 结构性护栏：凡是 Gson 处理的类，字段必须有 @SerializedName，
 *      且注解值必须等于字段名 —— 这样即使被混淆，JSON 键名也不变。
 *      这条断言如果早点存在，那次事故根本不会发生。
 *   2. 行为断言：实际用 Gson 序列化，检查产出的 JSON 键名是不是服务端要的那几个。
 */
public class GsonContractTest {

    private static final Gson gson = new Gson();

    /**
     * 需要被 Gson 处理的类清单（请求体 + 响应模型 + 本地持久化）。
     * 新增任何被 gson.toJson/fromJson 的类，都必须加进这里。
     */
    private static Class<?>[] gsonClasses() {
        List<Class<?>> list = new ArrayList<>();
        // 请求体（ApiClient 内部类，包级可见 → 本测试同包可直接引用）
        list.add(ApiClient.AuthBody.class);
        list.add(ApiClient.RegBody.class);
        list.add(ApiClient.FriendReqBody.class);
        list.add(ApiClient.RemarkBody.class);
        list.add(ApiClient.CallStatusBody.class);
        list.add(ApiClient.ChangePwdBody.class);
        list.add(ApiClient.ProfileUpdateBody.class);
        // 响应模型：Models 下所有静态嵌套类，靠反射收集，避免手工遗漏
        for (Class<?> nested : Models.class.getDeclaredClasses()) {
            if (Models.class.isAssignableFrom(nested) && !nested.isInterface()) {
                list.add(nested);
            }
        }
        return list.toArray(new Class<?>[0]);
    }

    /**
     * 结构性护栏：这些类的实例字段必须带 @SerializedName，且值 == 字段名。
     *
     * 为什么值必须等于字段名：这些字段名同时就是 JSON 键名。若注解值写成别的，
     * 序列化结果会与既有服务端/既有本地存档不兼容（升级后读不出旧数据）。
     */
    @Test
    public void allGsonFieldsMustDeclareSerializedName() {
        List<String> problems = new ArrayList<>();

        for (Class<?> clazz : gsonClasses()) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) continue;
                if (Modifier.isTransient(f.getModifiers())) continue; // Gson 默认忽略 transient

                SerializedName ann = f.getAnnotation(SerializedName.class);
                if (ann == null) {
                    problems.add(clazz.getSimpleName() + "." + f.getName()
                            + " 缺少 @SerializedName（混淆后会改变 JSON 键名）");
                } else if (!ann.value().equals(f.getName())) {
                    // 注解值必须是字段名本身：这些字段名同时就是服务端/本地存档的 JSON 键名。
                    // 若确实需要改字段名而保持旧键名，请在此处登记例外并写明原因。
                    problems.add(clazz.getSimpleName() + "." + f.getName()
                            + " 的 @SerializedName(\"" + ann.value() + "\") 与字段名不一致，"
                            + "会改变 JSON 键名导致接口 400 或读不出旧存档");
                }
            }
        }

        assertTrue("以下字段缺少字段名保护，release 包会因此出现接口 400 或本地数据读不出：\n  "
                + String.join("\n  ", problems), problems.isEmpty());
    }

    // ===== 行为断言：实际产出的 JSON 键名 =====

    @Test
    public void loginBody_serializesWithServerExpectedKeys() {
        ApiClient.AuthBody body = new ApiClient.AuthBody();
        body.username = "测试用户";
        body.password = "secret123";

        String json = gson.toJson(body);

        assertTrue("登录请求体必须含 username 键，实际: " + json, json.contains("\"username\""));
        assertTrue("登录请求体必须含 password 键，实际: " + json, json.contains("\"password\""));
        assertTrue("中文用户名不应被转义成 unicode 以外的东西，实际: " + json, json.contains("测试用户"));
        // 不能出现被混淆后的单字母键
        assertFalse("出现了疑似被混淆的短键名: " + json, json.matches(".*\"[a-z]\"\\s*:.*"));
    }

    @Test
    public void registerBody_serializesWithServerExpectedKeys() {
        ApiClient.RegBody body = new ApiClient.RegBody();
        body.username = "u1";
        body.password = "p1";
        body.nickname = "昵称";

        String json = gson.toJson(body);
        assertTrue(json.contains("\"username\""));
        assertTrue(json.contains("\"password\""));
        assertTrue(json.contains("\"nickname\""));
    }

    @Test
    public void changePwdBody_serializesWithServerExpectedKeys() {
        // 这组字段曾经真的被混淆掉（old_password/new_password 在 DEX 里消失）
        ApiClient.ChangePwdBody body = new ApiClient.ChangePwdBody();
        body.old_password = "old";
        body.new_password = "new";

        String json = gson.toJson(body);
        assertTrue("改密码接口需要 old_password，实际: " + json, json.contains("\"old_password\""));
        assertTrue("改密码接口需要 new_password，实际: " + json, json.contains("\"new_password\""));
    }

    @Test
    public void callStatusBody_serializesWithServerExpectedKeys() {
        ApiClient.CallStatusBody body = new ApiClient.CallStatusBody();
        body.friend_id = 7;
        body.event = "ended";
        body.duration_sec = 42;
        body.video = true;

        String json = gson.toJson(body);
        assertTrue(json.contains("\"friend_id\""));
        assertTrue(json.contains("\"event\""));
        assertTrue(json.contains("\"duration_sec\""));
        // 服务端靠它把 msg_type 落成 call_video / call_voice；漏掉这个 key 会让
        // 所有通话记录都退化成语音（服务端缺省 false），客户端图标与回拨类型全错。
        assertTrue(json.contains("\"video\""));
        // 缺省值必须是 false（= 语音），否则老路径/未赋值的调用会意外变成视频
        assertTrue(gson.toJson(new ApiClient.CallStatusBody()).contains("\"video\":false"));
    }

    @Test
    public void profileUpdateBody_serializesWithServerExpectedKeys() {
        ApiClient.ProfileUpdateBody body = new ApiClient.ProfileUpdateBody();
        body.nickname = "n";
        body.avatar = "😊";
        body.avatar_color = "#ffffff";

        String json = gson.toJson(body);
        assertTrue(json.contains("\"nickname\""));
        assertTrue(json.contains("\"avatar\""));
        assertTrue("服务端读的是 snake_case 的 avatar_color，实际: " + json,
                json.contains("\"avatar_color\""));
    }

    // ===== 反序列化：服务端返回的数据必须能正确填充模型 =====

    @Test
    public void authResponse_deserializesServerPayload() {
        String serverJson = "{\"token\":\"jwt.token.here\",\"user\":{\"id\":9,\"username\":\"测试用户\","
                + "\"nickname\":\"测试用户\",\"avatar\":\"😊\",\"avatar_color\":\"#e8f5e9\","
                + "\"is_online\":true,\"last_seen\":\"2026-09-11 20:00:00\",\"wx_id\":\"my_000009\"}}";

        Models.AuthResponse res = gson.fromJson(serverJson, Models.AuthResponse.class);

        assertNotNull(res);
        assertEquals("jwt.token.here", res.token);
        assertNotNull("user 未解析成功", res.user);
        assertEquals(9, res.user.id);
        assertEquals("测试用户", res.user.username);
        assertEquals("my_000009", res.user.wx_id);
        assertEquals("#e8f5e9", res.user.avatar_color);
        assertTrue("is_online 应为 true", res.user.is_online);
    }

    @Test
    public void errorResponse_messageIsReadableByClient() {
        // ApiClient 会把服务端的 error/message 显示给用户，字段名错了用户就看到
        // "服务器错误: 400" 这种无用信息
        Models.AuthResponse res = gson.fromJson(
                "{\"error\":\"用户名或密码错误\"}", Models.AuthResponse.class);
        assertNotNull(res);
        assertEquals("用户名或密码错误", res.error);

        Models.AuthResponse res2 = gson.fromJson(
                "{\"message\":\"密码修改成功\"}", Models.AuthResponse.class);
        assertNotNull(res2);
        assertEquals("密码修改成功", res2.message);
    }

    @Test
    public void groupMember_fieldsDeserialize() {
        // 群成员扩展字段：服务端 GroupDao 用 COALESCE 别名 group_nickname / mute_until
        String json = "{\"id\":3,\"username\":\"u\",\"nickname\":\"昵称\",\"avatar\":\"a\","
                + "\"avatar_color\":\"#fff\",\"is_online\":false,\"last_seen\":\"\",\"wx_id\":\"my_000003\","
                + "\"role\":\"admin\",\"group_nickname\":\"群名片\",\"mute_until\":1234567}";
        Models.User u = gson.fromJson(json, Models.User.class);
        assertEquals("admin", u.role);
        assertEquals("群名片", u.group_nickname);
        assertEquals(1234567L, u.mute_until);
    }

    @Test
    public void message_fieldsDeserialize() {
        String json = "{\"id\":1,\"sender_id\":2,\"receiver_id\":3,\"content\":\"hi\","
                + "\"msg_type\":\"text\",\"created_at\":\"2026-09-11 20:00:00\",\"is_read\":true,"
                + "\"duration_sec\":5,\"filename\":\"a.png\",\"thumbnail\":\"/t.jpg\"}";
        Models.Message m = gson.fromJson(json, Models.Message.class);
        assertEquals(1, m.id);
        assertEquals(2, m.sender_id);
        assertEquals("hi", m.content);
        assertTrue(m.is_read);
        assertEquals(5, m.duration_sec);
        assertEquals("a.png", m.filename);
    }

    @Test
    public void messagesResponse_hasMoreAndList() {
        String json = "{\"messages\":[{\"id\":1,\"content\":\"x\"}],\"has_more\":true}";
        Models.MessagesResponse r = gson.fromJson(json, Models.MessagesResponse.class);
        assertNotNull(r.messages);
        assertEquals(1, r.messages.size());
        assertTrue(r.has_more);
    }

    @Test
    public void mallOrder_deserializesAllStateFields() {
        String json = "{\"id\":1,\"order_no\":\"NO1\",\"product_id\":2,\"seller_id\":3,\"buyer_id\":4,"
                + "\"quantity\":1,\"amount\":100,\"status\":\"paid\",\"receiver_name\":\"张三\","
                + "\"receiver_phone\":\"13800000000\",\"receiver_address\":\"某地\",\"created_at\":1,"
                + "\"paid_at\":2,\"shipped_at\":3,\"completed_at\":4,\"cancel_time\":0,"
                + "\"shipping_company\":\"顺丰\",\"tracking_no\":\"SF1\",\"refund_status\":\"pending\","
                + "\"refund_reason\":\"不合适\",\"refund_time\":0,\"product_name\":\"商品\","
                + "\"product_image\":\"/i.jpg\",\"buyer_nickname\":\"买家\"}";
        Models.MallOrder o = gson.fromJson(json, Models.MallOrder.class);
        assertEquals("NO1", o.order_no);
        assertEquals("paid", o.status);
        assertEquals("张三", o.receiver_name);
        assertEquals("顺丰", o.shipping_company);
        assertEquals("pending", o.refund_status);
        assertEquals("买家", o.buyer_nickname);
    }

    @Test
    public void groupPoll_deserializesOptionsAndStatus() {
        String json = "{\"id\":1,\"group_id\":2,\"creator_id\":3,\"creator_name\":\"发起人\","
                + "\"title\":\"投票\",\"description\":\"说明\",\"is_multi\":true,\"anonymous\":false,"
                + "\"status\":\"open\",\"end_at\":\"\",\"expired\":false,\"total_voters\":2,"
                + "\"created_at\":\"x\",\"options\":[{\"id\":1,\"option_text\":\"A\",\"ordinal\":0,"
                + "\"vote_count\":1,\"my_voted\":true}]}";
        Models.GroupPoll p = gson.fromJson(json, Models.GroupPoll.class);
        assertEquals("投票", p.title);
        assertTrue(p.is_multi);
        assertEquals(1, p.options.size());
        assertEquals("A", p.options.get(0).option_text);
        assertTrue(p.options.get(0).my_voted);
    }

    @Test
    public void unknownJsonFields_doNotBreakDeserialization() {
        // 服务端加字段不能让老客户端崩（Gson 默认忽略未知字段，这里锁定该行为）
        String json = "{\"token\":\"t\",\"brand_new_field_from_future\":{\"a\":1},"
                + "\"user\":{\"id\":1,\"another_future_field\":true}}";
        Models.AuthResponse res = gson.fromJson(json, Models.AuthResponse.class);
        assertNotNull(res);
        assertEquals("t", res.token);
        assertEquals(1, res.user.id);
    }

    @Test
    public void missingOptionalFields_yieldDefaultsNotCrash() {
        String json = "{\"token\":\"t\"}";
        Models.AuthResponse res = gson.fromJson(json, Models.AuthResponse.class);
        assertNotNull(res);
        assertEquals("t", res.token);
        assertEquals(null, res.user);
    }
}
