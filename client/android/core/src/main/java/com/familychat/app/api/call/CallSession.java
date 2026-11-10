package com.familychat.app.api.call;

/**
 * 单次通话的数据载体（Step 1）。
 *
 * <p>纯数据、无逻辑、无 Android 依赖：状态机在 {@link CallManager}，本类只负责"一趟通话长什么样"。
 * 除了 {@link #state} / {@link #ringingAt} / {@link #activeAt} / {@link #offerSdp} 这几个运行态字段，
 * 其余都是构造时定死的不可变字段。
 *
 * <h3>为什么要有 callId</h3>
 * 改造前用「发起人 userId + 4 秒时间窗」去重（{@code CallActivity.shouldHandleIncoming}），
 * 后果是同一人 4 秒内重拨会被静默吞掉（现象 3-①）。callId 由主叫生成、随 call_offer 传播，
 * 服务端原样透传，两端都以它为唯一去重键 —— 既不受时间窗影响，也能识别"迟到的旧信令"。
 *
 * <p><b>线程约定</b>：所有可变字段只在主线程写（CallManager 的所有入口都在主线程），
 * 标 volatile 是为了让任意线程读时不会看到撕裂值（例如 UI 线程轮询状态）。
 */
public final class CallSession {

    /** 通话生命周期状态。IDLE 不在本对象上出现 —— "没有通话"由 {@code CallManager.current == null} 表示。 */
    public enum State {
        IDLE,
        /** 本端主叫，已发出/待发出 call_offer，等待对端接听。 */
        OUTGOING_RINGING,
        /** 本端被叫，正在响铃（响铃 UI 暂仍由 CallActivity 承担，Step 2 才拆独立响铃页）。 */
        INCOMING_RINGING,
        /** 双方已接通，媒体通路建立中/已建立。 */
        ACTIVE,
        /** 收尾瞬态：通知观察者期间的状态，随即回到 IDLE。 */
        ENDING
    }

    // ---------------- 不可变字段 ----------------

    /** 通话唯一 id（UUID），由主叫生成。 */
    public final String callId;
    /** 主叫 userId。 */
    public final int callerId;
    /** 被叫 userId。 */
    public final int calleeId;
    /** 是否视频通话（由主叫的 call_offer 携带）。 */
    public final boolean videoEnabled;
    /** true = 本端是被叫。它决定 {@link #peerId()} / {@link #selfId()} 的取值方向。 */
    public final boolean incoming;
    /** 本端创建该会话的时刻。 */
    public final long createdAt;

    // ---------------- 运行态字段 ----------------

    /** 当前状态。 */
    public volatile State state = State.IDLE;
    /** 进入 OUTGOING_RINGING / INCOMING_RINGING 的时刻（超时基准）。 */
    public volatile long ringingAt;
    /** 进入 ACTIVE 的时刻（通话时长基准、媒体看门狗基准）。 */
    public volatile long activeAt;
    /** 被叫侧保存的原始 offer（中继模式下是字面量 "relay"）。 */
    public volatile String offerSdp;

    public CallSession(String callId, int callerId, int calleeId,
                       boolean videoEnabled, boolean incoming) {
        this.callId = callId;
        this.callerId = callerId;
        this.calleeId = calleeId;
        this.videoEnabled = videoEnabled;
        this.incoming = incoming;
        this.createdAt = System.currentTimeMillis();
    }

    // ---------------- 只读派生 ----------------

    /** 对端 userId（本端视角）。信令收发一律对它。 */
    public int peerId() {
        return incoming ? callerId : calleeId;
    }

    /** 本端 userId。 */
    public int selfId() {
        return incoming ? calleeId : callerId;
    }

    /** 是否处于响铃阶段（主叫等待接听 / 被叫正在响铃）。 */
    public boolean isRinging() {
        return state == State.OUTGOING_RINGING || state == State.INCOMING_RINGING;
    }

    /** 是否"已接通"：这一判断决定挂断时要发 call_hangup 还是 call_cancel/call_reject。 */
    public boolean isActive() {
        return state == State.ACTIVE;
    }

    /** 接通后的已流逝毫秒数；未接通返回 0。 */
    public long activeElapsedMs() {
        return activeAt > 0 ? System.currentTimeMillis() - activeAt : 0;
    }

    /** 接通时长（秒），用于"通话时长 MM:SS"系统消息。 */
    public int activeElapsedSec() {
        long ms = activeElapsedMs();
        return ms <= 0 ? 0 : (int) (ms / 1000);
    }

    /**
     * 该信令是否属于本次通话。
     *
     * <p>优先按 callId 匹配；callId 缺失时退化为"对端 userId 匹配"。
     * 退化分支存在的原因：{@code call_hangup} / {@code call_answer} 等信令本批<b>没有</b>
     * 带 callId（不改既有 Listener 签名），只能靠对端 id 认领 —— 这是安全的，
     * 因为状态机保证同一时刻最多只有一通电话。
     */
    public boolean matches(int fromUserId, String signalCallId) {
        if (signalCallId != null && !signalCallId.isEmpty() && signalCallId.equals(callId)) {
            return true;
        }
        return peerId() == fromUserId;
    }

    @Override
    public String toString() {
        return "CallSession{" + callId + " " + state
                + " caller=" + callerId + " callee=" + calleeId
                + " incoming=" + incoming + " video=" + videoEnabled + "}";
    }
}
