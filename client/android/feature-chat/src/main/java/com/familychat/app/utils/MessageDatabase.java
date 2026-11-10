package com.familychat.app.utils;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.familychat.app.api.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * Local SQLite database for persistent chat message history.
 * Messages survive app restarts and are never deleted unless user explicitly removes them.
 */
public class MessageDatabase extends SQLiteOpenHelper {

    private static final String TAG = "MessageDatabase";
    private static final String DB_NAME = "chat_local.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_MESSAGES = "messages";
    private static final String COL_ID = "id";
    private static final String COL_SENDER_ID = "sender_id";
    private static final String COL_RECEIVER_ID = "receiver_id";
    private static final String COL_CONTENT = "content";
    private static final String COL_MSG_TYPE = "msg_type";
    private static final String COL_CREATED_AT = "created_at";
    private static final String COL_IS_READ = "is_read";
    private static final String COL_DURATION_SEC = "duration_sec";

    private static final String CREATE_TABLE =
            "CREATE TABLE " + TABLE_MESSAGES + " (" +
                    COL_ID + " INTEGER PRIMARY KEY," +
                    COL_SENDER_ID + " INTEGER NOT NULL," +
                    COL_RECEIVER_ID + " INTEGER NOT NULL," +
                    COL_CONTENT + " TEXT," +
                    COL_MSG_TYPE + " TEXT DEFAULT 'text'," +
                    COL_CREATED_AT + " TEXT," +
                    COL_IS_READ + " INTEGER DEFAULT 1," +
                    COL_DURATION_SEC + " INTEGER DEFAULT 0)";

    private static MessageDatabase instance;

    public static synchronized MessageDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new MessageDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private MessageDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE);
        Log.d(TAG, "database created");
    }

    /**
     * 逐版本增量迁移。**绝不 DROP TABLE。**
     *
     * <h3>为什么改</h3>
     * 历史实现是：
     * <pre>
     *   db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
     *   onCreate(db);
     * </pre>
     * 由于 {@link #DB_VERSION} 一直是 1，这段代码从未执行过 —— 但只要将来为了任何功能
     * 给 {@code messages} 加一列而把 DB_VERSION 提升，**所有用户的全部本地聊天历史会在
     * 升级后的第一次打开数据库时被静默清空**，且没有任何备份或提示。
     * 本地库是会话列表预览的唯一数据源（MainActivity 只读本地库取 last message），
     * 而 E2EE 决定了服务端只有密文、且只保留最近 100/200 条，因此这部分历史无法找回。
     *
     * <h3>现在的规则</h3>
     * 每个 DB_VERSION 对应一个 case，只写「从上一版到这一版」需要的那一步；
     * 用循环逐个执行，因此从 v1 直接跳到 v4 也会按 2 → 3 → 4 的顺序全部跑一遍。
     *
     * <h3>新增迁移时注意</h3>
     * <ul>
     *   <li>SQLite 的 {@code ALTER TABLE ADD COLUMN} 不支持加 UNIQUE 约束，
     *       也不支持加「NOT NULL 且无默认值」的列。需要这类改动时，必须走
     *       「建新表 → INSERT INTO … SELECT … → DROP 旧表 → RENAME」四步，
     *       并自行保证数据搬迁完整。</li>
     *   <li>迁移语句要能重复执行而不报错，或用 try/catch 包住「列已存在」的情况。</li>
     *   <li>改完 DB_VERSION 后，务必真机验证一次「老库升级」路径，而不只是清数据安装。</li>
     * </ul>
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        for (int v = oldVersion + 1; v <= newVersion; v++) {
            switch (v) {
                case 2:
                    // 预留：下一版要给 messages 加列时写在这里。
                    // 例（仅作示例，本步不新增任何列）：
                    //   db.execSQL("ALTER TABLE " + TABLE_MESSAGES
                    //           + " ADD COLUMN reply_to INTEGER DEFAULT 0");
                    break;
                case 3:
                    // 预留：第三个版本的迁移步骤。
                    break;
                case 4:
                    // 预留：第四个版本的迁移步骤。
                    break;
                default:
                    // 有版本号但没有登记迁移步骤。
                    // 关键：这里绝不能退化成 DROP TABLE + onCreate —— 那正是本次修复要根除的行为。
                    // 选择「大声记日志后继续」而不是抛异常：抛异常会让 getWritableDatabase()
                    // 整体失败，聊天功能全挂；而缺迁移通常只在真正用到新列的那个页面才会
                    // 抛 SQLiteException，影响面小得多，也更早被发现。
                    Log.e(TAG, "onUpgrade: 没有为 v" + v + " 登记迁移步骤，已跳过"
                            + "（绝不 DROP TABLE，用户历史必须保留）");
                    break;
            }
        }
    }

    // =================================================================
    //  CRUD
    // =================================================================

    /** Insert or replace a message. */
    public synchronized void insertMessage(Models.Message msg) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_ID, msg.id);
        cv.put(COL_SENDER_ID, msg.sender_id);
        cv.put(COL_RECEIVER_ID, msg.receiver_id);
        cv.put(COL_CONTENT, msg.content);
        cv.put(COL_MSG_TYPE, msg.msg_type);
        cv.put(COL_CREATED_AT, msg.created_at);
        cv.put(COL_IS_READ, msg.is_read ? 1 : 0);
        cv.put(COL_DURATION_SEC, msg.duration_sec);
        db.insertWithOnConflict(TABLE_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        db.close();
    }

    /** Insert a batch of messages (e.g. from server sync). */
    public synchronized void insertMessages(List<Models.Message> msgs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Models.Message msg : msgs) {
                ContentValues cv = new ContentValues();
                cv.put(COL_ID, msg.id);
                cv.put(COL_SENDER_ID, msg.sender_id);
                cv.put(COL_RECEIVER_ID, msg.receiver_id);
                cv.put(COL_CONTENT, msg.content);
                cv.put(COL_MSG_TYPE, msg.msg_type);
                cv.put(COL_CREATED_AT, msg.created_at);
                cv.put(COL_IS_READ, msg.is_read ? 1 : 0);
                cv.put(COL_DURATION_SEC, msg.duration_sec);
                db.insertWithOnConflict(TABLE_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        db.close();
    }

    /** Get all messages between current user and a friend, ordered by id ascending. */
    public synchronized List<Models.Message> getMessages(int myId, int friendId) {
        List<Models.Message> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_MESSAGES, null,
                "(" + COL_SENDER_ID + "=? AND " + COL_RECEIVER_ID + "=?) OR (" +
                        COL_SENDER_ID + "=? AND " + COL_RECEIVER_ID + "=?)",
                new String[]{String.valueOf(myId), String.valueOf(friendId),
                        String.valueOf(friendId), String.valueOf(myId)},
                null, null, COL_ID + " ASC");
        while (c.moveToNext()) {
            result.add(cursorToMessage(c));
        }
        c.close();
        db.close();
        return result;
    }

    /** 与某好友的最后一条消息（会话预览/时间用），无则返回 null。 */
    public synchronized Models.Message getLastMessage(int myId, int friendId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(TABLE_MESSAGES, null,
                "(" + COL_SENDER_ID + "=? AND " + COL_RECEIVER_ID + "=?) OR (" +
                        COL_SENDER_ID + "=? AND " + COL_RECEIVER_ID + "=?)",
                new String[]{String.valueOf(myId), String.valueOf(friendId),
                        String.valueOf(friendId), String.valueOf(myId)},
                null, null, COL_ID + " DESC", "1");
        Models.Message msg = null;
        if (c.moveToNext()) msg = cursorToMessage(c);
        c.close();
        db.close();
        return msg;
    }

    /** Delete a single message by its server ID. */
    public synchronized void deleteMessage(int msgId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, COL_ID + "=?", new String[]{String.valueOf(msgId)});
        db.close();
    }

    /** Mark a message as recalled in local DB by message ID. */
    public synchronized void recallMessage(int msgId) {
        SQLiteDatabase db = getWritableDatabase();
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(COL_CONTENT, "[已撤回]");
        cv.put(COL_MSG_TYPE, "recalled");
        db.update(TABLE_MESSAGES, cv, COL_ID + "=?", new String[]{String.valueOf(msgId)});
        db.close();
        android.util.Log.d("RECALL_LOG", "local DB recall: msgId=" + msgId);
    }

    /** Delete all messages between user and a friend (clear conversation). */
    public synchronized void deleteConversation(int myId, int friendId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES,
                "(" + COL_SENDER_ID + "=? AND " + COL_RECEIVER_ID + "=?) OR (" +
                        COL_SENDER_ID + "=? AND " + COL_RECEIVER_ID + "=?)",
                new String[]{String.valueOf(myId), String.valueOf(friendId),
                        String.valueOf(friendId), String.valueOf(myId)});
        db.close();
    }

    /** Wipe the entire local message cache (used on logout to prevent cross-account data). */
    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MESSAGES, null, null);
        db.close();
    }

    private Models.Message cursorToMessage(Cursor c) {
        Models.Message msg = new Models.Message();
        msg.id = c.getInt(c.getColumnIndexOrThrow(COL_ID));
        msg.sender_id = c.getInt(c.getColumnIndexOrThrow(COL_SENDER_ID));
        msg.receiver_id = c.getInt(c.getColumnIndexOrThrow(COL_RECEIVER_ID));
        msg.content = c.getString(c.getColumnIndexOrThrow(COL_CONTENT));
        msg.msg_type = c.getString(c.getColumnIndexOrThrow(COL_MSG_TYPE));
        msg.created_at = c.getString(c.getColumnIndexOrThrow(COL_CREATED_AT));
        msg.is_read = c.getInt(c.getColumnIndexOrThrow(COL_IS_READ)) == 1;
        msg.duration_sec = c.getInt(c.getColumnIndexOrThrow(COL_DURATION_SEC));
        return msg;
    }
}
