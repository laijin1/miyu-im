package com.familychat.server;

import com.familychat.server.model.Database;
import com.familychat.server.model.dao.MallDao;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商城只读查询的失败语义：查询失败必须返回 {@code null}（由 handler 映射为 5xx），
 * 绝不能伪装成「空数据」。
 *
 * 对应体检报告 §2.4（MallDao 吞异常返回成功）：
 * 原先 SQLException 被吞掉后返回空列表，handler 回 200 + total:0 / items:[]，
 * 用户在客户端看到的是「购物车空了」「暂无评价」，而不是「服务端出错了」。
 *
 * 用「连接必失败」的 Database 子类确定性触发 SQLException，不需要真的把库弄坏。
 */
class MallQueryFailureTest extends DbTestSupport {

    /** 所有查询都失败的 Database：getConn 直接抛异常，覆盖 DAO 的 catch 分支。 */
    private static final class FailingDatabase extends Database {
        @Override
        public Connection getConn() throws SQLException {
            throw new SQLException("simulated DB failure (MallQueryFailureTest)");
        }
    }

    private MallDao failingDao() {
        return new MallDao(new FailingDatabase());
    }

    @Test
    void cartQueryFailureReturnsNullNotNullEmptyList() {
        assertNull(failingDao().getCart(1),
                "购物车查询失败必须返回 null（handler 才能映射为 5xx），不得返回空列表");
    }

    @Test
    void favoritesQueryFailureReturnsNullNotAnEmptyList() {
        assertNull(failingDao().getFavorites(1),
                "收藏查询失败必须返回 null（handler 才能映射为 5xx），不得返回空列表");
    }

    @Test
    void reviewsQueryFailureReturnsNullNotAnEmptyList() {
        assertNull(failingDao().getProductReviews(1),
                "评价查询失败必须返回 null（handler 才能映射为 5xx），不得返回空列表");
    }

    /** 反向保证：正常库 + 无数据时，必须返回空列表（而不是 null）——「无数据」不等于「失败」。 */
    @Test
    void emptyResultOnHealthyDbStaysAnEmptyList() {
        int uid = register("mallqueryok");
        assertNotNull(db.getCart(uid), "无购物车数据时应返回空列表，不得返回 null");
        assertTrue(db.getCart(uid).isEmpty(), "新建用户的购物车应为空列表");
        assertNotNull(db.getFavorites(uid), "无收藏时应返回空列表，不得返回 null");
        assertTrue(db.getFavorites(uid).isEmpty(), "新建用户的收藏应为空列表");
        assertNotNull(db.getProductReviews(1), "商品无评价时应返回空列表，不得返回 null");
        assertTrue(db.getProductReviews(1).isEmpty(), "无评价时应为空列表");
    }
}
