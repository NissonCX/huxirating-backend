-- 性能优化索引
-- 执行前请先备份数据库

-- 优惠券订单表索引
-- 复合索引：用于用户订单查询 + 一人一单校验
ALTER TABLE tb_voucher_order ADD INDEX idx_user_voucher_status (user_id, voucher_id, status);

-- 单列索引：用于按优惠券ID查询订单
ALTER TABLE tb_voucher_order ADD INDEX idx_voucher_id (voucher_id);

-- 博客表索引
-- 用于按用户ID查询博客列表
ALTER TABLE tb_blog ADD INDEX idx_user_id (user_id);

-- 博客评论表索引
-- 用于按博客ID查询评论列表
ALTER TABLE tb_blog_comments ADD INDEX idx_blog_id (blog_id);

-- 关注表索引
-- 用于查询粉丝列表和关注列表
ALTER TABLE tb_follow ADD INDEX idx_follow_user_id (follow_user_id);
ALTER TABLE tb_follow ADD INDEX idx_user_id (user_id);

-- 注意：一人一单唯一约束需谨慎添加
-- 如果业务允许用户取消后重新购买同一优惠券，则不应添加此约束
-- ALTER TABLE tb_voucher_order ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
