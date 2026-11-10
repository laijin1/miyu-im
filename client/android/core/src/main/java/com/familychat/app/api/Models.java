package com.familychat.app.api;

import java.util.List;

public class Models {

    // ===== Auth =====
    public static class AuthResponse {
        public String token;
        public User user;
        public String error;
        public String message; // 部分接口用 message 返回错误/成功信息
    }

    // ===== User =====
    public static class User {
        public int id;
        public String username;
        public String nickname;
        public String avatar;
        public String avatar_color;
        public boolean is_online;
        public String last_seen;
        public String wx_id;   // 密语号式专属ID
        public String remark; // 好友备注（非好友为 null/空）
        // ---- 群成员扩展字段（getGroupMembers 返回） ----
        public String role;            // owner/admin/member
        public String group_nickname;  // 群名片（群内昵称），空=用全局昵称
        public long mute_until;        // 禁言截止（毫秒，0=未禁言，-1=永久）
    }

    // ===== Friend =====
    public static class FriendRequest {
        public int friendship_id;
        public User user;
        public String created_at;
        public String status;
    }

    // ===== Message =====
    public static class Message {
        public int id;
        public int sender_id;
        public int receiver_id;
        public String content;
        public String msg_type;
        public String created_at;
        public boolean is_read;
        public int duration_sec; // voice message duration in seconds
        public String filename;  // 图片/文件消息的原始文件名（搜索用）
        public String thumbnail; // 视频消息的缩略图 URL（明文预览图）

        /**
         * 毫秒时间戳（时间行判断用）。服务端只回传字符串 created_at，
         * 由 Adapter 首次解析 created_at 后填充缓存，之后复用（Gson 不反序列化此字段）。
         */
        public long timestamp;
    }

    // ===== API Wrappers =====
    public static class UserListResponse {
        public List<User> users;
        public String error;
    }

    public static class FriendsResponse {
        public List<User> friends;
        public String error;
    }

    public static class FriendRequestsResponse {
        public List<FriendRequest> requests;
        public String error;
    }

    public static class MessagesResponse {
        public List<Message> messages;
        public boolean has_more;
        public String error;
    }

    public static class UnreadResponse {
        public java.util.Map<String, Object> unread;
        public String error;
    }

    public static class ProfileResponse {
        public User user;
        public String error;
    }

    public static class FriendRequestResult {
        public int friendship_id;
        public String status;
        public String error;
    }

    public static class StatusResponse {
        public boolean success;
        public String error;
    }

    // ===== 群聊 =====
    public static class Group {
        public int id;
        public String name;
        public String avatar;
        public int owner_id;
        public int member_count;
        public int unread;
        // 最后一条消息预览
        public int last_message_id;
        public int last_sender_id;
        public String last_content;
        public String last_msg_type;
        public String last_filename;
        public String last_thumbnail;
        public String last_created_at;
        public String created_at;
        // ---- 权限/设置字段 ----
        public String my_role;        // 我在群里的角色 owner/admin/member
        public String my_nickname;    // 我的群名片
        public int muted;             // 1=我在本群被禁言
        public int latest_announcement_id; // 最新公告 id（横幅用，0=无）
        public int pinned;            // 1=我在本群置顶会话
        public int quiet;             // 1=本群消息免打扰
    }

    // ===== 群会话设置 =====
    public static class GroupSettings {
        public int pinned;            // 1=置顶
        public int quiet;             // 1=免打扰
        public String error;
    }

    public static class GroupListResponse {
        public List<Group> groups;
        public String error;
    }

    public static class GroupDetailResponse {
        public Group group;
        public List<User> members;
        public String error;
    }

    public static class GroupMessage {
        public int id;
        public int group_id;
        public int sender_id;
        public String content;
        public String msg_type;
        public String filename;
        public String thumbnail;
        public int duration_sec; // voice message duration in seconds
        public String mentions;  // @提及："all" 或 "[uid,...]"（明文 uid 列表，服务端只路由不读正文）
        public String created_at;

        /**
         * 毫秒时间戳（时间行判断用）。服务端只回传字符串 created_at，
         * 由 Adapter 首次解析 created_at 后填充缓存，之后复用（Gson 不反序列化此字段）。
         */
        public long timestamp;
    }

    public static class GroupMessagesResponse {
        public List<GroupMessage> messages;
        public String error;
    }

    // ===== 群公告 =====
    public static class GroupAnnouncement {
        public int id;
        public int group_id;
        public int publisher_id;
        public String publisher_name;
        public String content;   // {成员id:密文} JSON，客户端解密展示
        public int pinned;       // 1=置顶
        public String created_at;
        public String updated_at;
        public int read_count;   // 已读人数
        public int member_count; // 群成员总数（X/Y 的 Y）
        public List<Integer> read_by;         // 已读成员 uid 列表
        public List<ReadUser> read_users;     // 已读成员名单（含昵称）
    }

    public static class ReadUser {
        public int id;
        public String name;
    }

    public static class AnnouncementListResponse {
        public List<GroupAnnouncement> announcements;
        public int member_count;
        public String error;
    }

    public static class AnnouncementResponse {
        public GroupAnnouncement announcement;
        public String error;
    }

    public static class MarkReadResponse {
        public int marked_read;
        public String error;
    }

    // ===== 群文件库 / 群相册（阶段4）=====
    public static class GroupFile {
        public int id;
        public int group_id;
        public int uploader_id;
        public String uploader_name;
        public String filename;    // 展示名
        public String file_url;    // /chat_files/xxx（明文 URL，仅群成员可访问）
        public String thumbnail;
        public long size_bytes;
        public String folder;      // 分类：聊天图片/聊天视频/聊天语音/群文件/自定义
        public String msg_type;    // image/video/file/voice
        public int file_msg_id;    // 关联的群消息 id（0=手动入库）
        public String created_at;
    }

    public static class GroupFilesResponse {
        public List<GroupFile> files;
        public String error;
    }

    public static class FileFolder {
        public String folder;
        public int count;
    }

    public static class FileFoldersResponse {
        public List<FileFolder> folders;
        public String error;
    }

    public static class AddFileResponse {
        public boolean success;
        public int file_id;
        public GroupFile file;
        public String error;
    }

    public static class GroupAlbum {
        public int id;
        public int group_id;
        public String name;
        public String cover_url;   // 最新一张照片 URL 或相册封面
        public int created_by;
        public int photo_count;
        public String created_at;
    }

    public static class GroupAlbumsResponse {
        public List<GroupAlbum> albums;
        public String error;
    }

    public static class AlbumPhoto {
        public int id;
        public int album_id;
        public int uploader_id;
        public String uploader_name;
        public String photo_url;
        public String thumbnail;
        public String created_at;
    }

    public static class AlbumPhotosResponse {
        public List<AlbumPhoto> photos;
        public String error;
    }

    public static class AddPhotosResponse {
        public boolean success;
        public List<Integer> photo_ids;
        public String error;
    }

    // ===== 群投票 =====
    public static class PollOption {
        public int id;
        public String option_text;
        public int ordinal;
        public int vote_count;
        public boolean my_voted;
    }

    public static class GroupPoll {
        public int id;
        public int group_id;
        public int creator_id;
        public String creator_name;
        public String title;
        public String description;
        public boolean is_multi;      // 单选/多选
        public boolean anonymous;     // 匿名：隐藏实名，仅显示人数
        public String status;         // open/closed
        public String end_at;         // 截止时间 yyyy-MM-dd HH:mm:ss，空=不限时
        public boolean expired;       // 服务端判定已过期（禁投）
        public int total_voters;      // 已投人数（去重）
        public String created_at;
        public List<PollOption> options;
    }

    public static class PollsResponse {
        public List<GroupPoll> polls;
        public String error;
    }

    public static class PollResponse {
        public boolean success;
        public GroupPoll poll;
        public String error;
    }

    // ===== 支付系统（阶段1起）=====
    /** 钱包信息（GET /api/wallet）。balance 单位:分。 */
    public static class WalletInfo {
        public long balance;   // 分
        public int pay_set;    // 1=已设置支付密码
        public String nickname;
        public String avatar;
        public String error;
    }

    public static class WalletResponse {
        public boolean success;
        public long balance;
        public int pay_set;
        public String error;
    }

    /** 账单流水项（GET /api/wallet/bills，阶段5）。 */
    public static class BillItem {
        public int id;
        public String type;      // transfer|red_packet|recharge|paycode
        public long amount;      // 分
        public String status;    // pending|accepted|refunded|completed
        public String direction; // income|expense
        public int peer_id;
        public String peer_nickname;
        public String peer_avatar;
        public String memo;
        public long created_at;  // 毫秒
    }

    public static class BillsResponse {
        public List<BillItem> bills;
        public boolean has_more;
        public String error;
    }

    /** 转账详情（GET /api/wallet/transfer/{tid}，阶段5）。 */
    public static class TransferDetail {
        public long transfer_id;
        public int from_user, to_user;
        public long amount;      // 分
        public String memo, status, created_at;
        public String from_nickname, from_avatar, to_nickname, to_avatar;
    }

    public static class TransferDetailResponse {
        public TransferDetail transfer;
        public String error;
    }

    /** 收款请求（POST /api/wallet/charge，阶段4）。 */
    public static class ChargeResponse {
        public boolean success;
        public long charge_id;
        public String error;
    }

    /** 转账响应（POST /api/wallet/transfer，阶段2）：返回服务端落库的 transfer 消息，供聊天即时上屏。 */
    public static class TransferSendResponse {
        public boolean success;
        public Message message;   // 服务端生成的 msg_type=transfer 消息
        public String error;
    }

    /** 红包详情（阶段3）。 */
    public static class RedPacketClaim {
        public int user_id;
        public String nickname;
        public long amount;      // 分
        public long claimed_at;  // 毫秒
    }

    public static class RedPacketDetail {
        public int id;
        public int group_id;
        public int sender;
        public String type;      // normal|lucky
        public long total;       // 分
        public int count;
        public long remain_amount;
        public int remain_count;
        public String memo;
        public String status;    // open|finished|refunded
        public List<RedPacketClaim> claims;
        public long my_claim;    // 我抢到的金额(分)，0=未抢
        public String error;
    }

    // ===== 商城（淘宝式闭环，阶段2+3+4完善）=====

    public static class Product {
        public int id;
        public String name;
        public String category;
        public long price;        // 分
        public String description;
        public String image_url;  // 相对路径，空串=无图（客户端兜底）
        public long sales;        // 销量（已售）
        public int stock;         // 库存
        public int on_sale;       // 1=上架 0=下架（商家后台用）
        public int seller_id;     // 卖家 id
        public String seller_nickname; // 卖家昵称（COALESCE 兜底"官方"）
    }

    public static class ProductsResponse {
        public List<Product> products;
        public boolean has_more;  // 分页：是否还有下一页
        public int total;         // 分页：总条数
        public String error;
    }

    public static class ProductDetailResponse {
        public Product product;
        public String error;
    }

    /** 订单（淘宝式状态机 pending待付款/paid待发货/shipped待收货/completed已完成/cancelled已关闭/refunded已退款）。 */
    public static class MallOrder {
        public int id;
        public String order_no;
        public int product_id;
        public int seller_id;
        public int buyer_id;
        public int quantity;
        public long amount;          // 分
        public String status;        // pending|paid|shipped|completed|cancelled|refunded
        public String receiver_name;
        public String receiver_phone;
        public String receiver_address;
        public long created_at;
        public long paid_at;
        public long shipped_at;
        public long completed_at;
        public long cancel_time;     // 取消时间（自动超时取消/手动取消）
        public String shipping_company; // 快递公司（发货时填写）
        public String tracking_no;      // 快递单号
        public String refund_status;    // ''无退款 | pending申请中 | approved已退款 | rejected已拒绝
        public String refund_reason;    // 退款原因
        public long refund_time;        // 退款完成时间
        public String product_name;
        public String product_image;
        public String buyer_nickname;
    }

    public static class MallOrderListResponse {
        public List<MallOrder> orders;
        public String error;
    }

    public static class MallOrderResponse {
        public MallOrder order;
        public String error;
    }

    public static class CreateOrderResponse {
        public boolean success;
        public MallOrder order;
        public String error;
    }

    /** 批量下单（购物车结算）。 */
    public static class CreateOrdersResponse {
        public boolean success;
        public List<MallOrder> orders;
        public String error;
    }

    /** 购物车项（含商品信息）。 */
    public static class CartItem {
        public int cart_id;
        public int product_id;
        public int quantity;
        public long created_at;
        public String name;
        public long price;       // 分
        public String image_url;
        public int stock;
        public int on_sale;
        public long sales;
    }

    public static class CartResponse {
        public List<CartItem> items;
        public String error;
    }

    /** 收藏项（含商品信息）。 */
    public static class FavoriteItem {
        public int fav_id;
        public int product_id;
        public long created_at;
        public String name;
        public long price;
        public String image_url;
        public int stock;
        public int on_sale;
        public long sales;
        public int seller_id;
        public String seller_nickname;
    }

    public static class FavoritesResponse {
        public List<FavoriteItem> favorites;
        public String error;
    }

    public static class FavoriteStatusResponse {
        public boolean favorited;
        public String error;
    }

    /** 收货地址。 */
    public static class Address {
        public int id;
        public String name;
        public String phone;
        public String address;
        public int is_default;
    }

    public static class AddressListResponse {
        public List<Address> addresses;
        public String error;
    }

    /** 商品评价。 */
    public static class Review {
        public int id;
        public int rating;
        public String content;
        public long created_at;
        public int user_id;
        public String nickname;
    }

    public static class ReviewListResponse {
        public List<Review> reviews;
        public String error;
    }

    /** 搜索热词。 */
    public static class HotSearch {
        public String keyword;
        public int count;
    }

    public static class HotSearchesResponse {
        public List<HotSearch> keywords;
        public String error;
    }

    /** 卖家店铺主页。 */
    public static class ShopResponse {
        public int seller_id;
        public String shop_name;
        public List<Product> products;
        public String error;
    }

    /** 分类（首页金刚区宫格）。 */
    public static class Category {
        public int id;
        public String name;
        public String color;      // 十六进制色值，如 #FF5000
        public int sort_order;
    }

    public static class CategoriesResponse {
        public List<Category> categories;
        public String error;
    }
}
