package com.huxirating.degradation;

import com.huxirating.dto.Result;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.service.IVoucherOrderService;
import com.huxirating.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀请求排队服务（故障期间用户不"干等"）
 * <p>
 * 【第二轮修复】解决三个核心问题：
 * 1. blockHandler 总是进入排队，不再直接拒绝
 * 2. 排队名额与库存挂钩，避免"虚假承诺"
 * 3. 消费时 DB 乐观锁保证不超卖
 * <p>
 * 关键设计：
 * - 每个券的排队上限 = min(库存 × OVERSUBSCRIBE_RATIO, MAX_QUEUE_PER_VOUCHER)
 * - 入队时预检查库存，但消费时才真正扣减（乐观锁）
 * - 用户看到的是"排队抢购资格"，不是"必得承诺"
 *
 * @author Nisson
 */
@Service
public class SeckillQueueService {
    private static final Logger log = LoggerFactory.getLogger(SeckillQueueService.class);

    @Resource
    private DegradationService degradationService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 排队请求队列（每个 voucherId 一个队列）
     */
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<QueueRequest>> queueMap = new ConcurrentHashMap<>();

    /**
     * 每个券的排队人数统计
     */
    private final ConcurrentHashMap<Long, AtomicLong> queueSizeMap = new ConcurrentHashMap<>();

    /**
     * 排队状态查询（ticketId -> 状态）
     */
    private final ConcurrentHashMap<String, QueueStatus> statusMap = new ConcurrentHashMap<>();

    /**
     * 用户排队记录（userId:voucherId -> ticketId），防止重复排队
     */
    private final ConcurrentHashMap<String, String> userQueueMap = new ConcurrentHashMap<>();

    /**
     * 后台消费线程池
     */
    private final ExecutorService consumerPool = Executors.newFixedThreadPool(4);

    /**
     * 排队超售比例：允许排队人数 = 库存 × 该比例
     * 比如库存 100，比例 3，则最多 300 人排队
     */
    private static final int OVERSUBSCRIBE_RATIO = 3;

    /**
     * 单券最大排队人数
     */
    private static final int MAX_QUEUE_PER_VOUCHER = 10000;

    /**
     * 全局排队计数器
     */
    private final AtomicLong totalQueued = new AtomicLong(0);
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);
    private final AtomicLong totalSoldOut = new AtomicLong(0);

    /**
     * 票据 ID 生成器
     */
    private final AtomicLong ticketIdGenerator = new AtomicLong(0);

    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        // 启动 4 个消费线程
        for (int i = 0; i < 4; i++) {
            consumerPool.submit(this::consumeQueue);
        }
        log.info("【排队服务】已启动，消费线程数：4，超售比例：{}", OVERSUBSCRIBE_RATIO);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        consumerPool.shutdown();
        log.info("【排队服务】已关闭");
    }

    /**
     * 尝试入队排队
     * <p>
     * 【关键修复】排队名额与库存挂钩，避免虚假承诺
     */
    public Result enqueue(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        String userKey = userId + ":" + voucherId;

        // 1. 检查是否已排队（快速返回）
        String existingTicketId = userQueueMap.get(userKey);
        if (existingTicketId != null) {
            QueueStatus existingStatus = statusMap.get(existingTicketId);
            if (existingStatus != null && !existingStatus.isCompleted()) {
                // 返回已有排队信息
                int position = getQueuePosition(voucherId, existingTicketId);
                Map<String, Object> result = new ConcurrentHashMap<>();
                result.put("ticketId", existingTicketId);
                result.put("status", "QUEUED");
                result.put("queuePosition", position);
                result.put("message", "您已在排队中，请勿重复提交");
                return Result.ok(result);
            }
        }

        // 2. 检查库存（快速失败）
        Integer stock = degradationService.getStockWithCache(voucherId);
        if (stock == null || stock <= 0) {
            totalSoldOut.incrementAndGet();
            return Result.fail("库存不足");
        }

        // 3. 【关键】计算该券的排队上限 = min(库存 × 超售比例, 最大值)
        int maxQueueForVoucher = Math.min(stock * OVERSUBSCRIBE_RATIO, MAX_QUEUE_PER_VOUCHER);

        // 4. 检查该券排队人数是否已满
        AtomicLong queueSize = queueSizeMap.computeIfAbsent(voucherId, k -> new AtomicLong(0));
        long currentSize = queueSize.get();

        if (currentSize >= maxQueueForVoucher) {
            totalRejected.incrementAndGet();
            return Result.fail("排队人数已满，请稍后再试");
        }

        // 5. CAS 增加排队人数（防止超额）
        if (!queueSize.compareAndSet(currentSize, currentSize + 1)) {
            totalRejected.incrementAndGet();
            return Result.fail("排队人数已满，请稍后再试");
        }

        // 6. 创建排队票据
        String ticketId = "T" + ticketIdGenerator.incrementAndGet();
        QueueRequest request = new QueueRequest();
        request.setTicketId(ticketId);
        request.setUserId(userId);
        request.setVoucherId(voucherId);
        request.setCreateTime(System.currentTimeMillis());

        // 7. 初始化排队状态
        QueueStatus queueStatus = new QueueStatus();
        queueStatus.setTicketId(ticketId);
        queueStatus.setUserKey(userKey);
        queueStatus.setVoucherId(voucherId);
        queueStatus.setStatus("QUEUED");
        queueStatus.setCreateTime(System.currentTimeMillis());
        statusMap.put(ticketId, queueStatus);
        userQueueMap.put(userKey, ticketId);

        // 8. 入队
        queueMap.computeIfAbsent(voucherId, k -> new ConcurrentLinkedQueue<>()).add(request);
        totalQueued.incrementAndGet();

        // 9. 计算排队位置
        int queuePosition = getQueuePosition(voucherId, ticketId);

        log.info("【排队】用户入队: ticketId={}, userId={}, voucherId={}, 库存={}, 排队位置={}/{}",
                ticketId, userId, voucherId, stock, queuePosition, maxQueueForVoucher);

        Map<String, Object> result = new ConcurrentHashMap<>();
        result.put("ticketId", ticketId);
        result.put("status", "QUEUED");
        result.put("queuePosition", queuePosition);
        result.put("stock", stock);
        result.put("maxQueue", maxQueueForVoucher);
        result.put("message", "您已进入抢购排队，前方还有 " + queuePosition + " 人");

        return Result.ok(result);
    }

    /**
     * 查询排队状态
     */
    public Result queryStatus(String ticketId) {
        QueueStatus status = statusMap.get(ticketId);
        if (status == null) {
            return Result.fail("票据不存在或已过期");
        }

        Map<String, Object> result = new ConcurrentHashMap<>();
        result.put("ticketId", ticketId);
        result.put("status", status.getStatus());

        if ("QUEUED".equals(status.getStatus())) {
            int position = getQueuePosition(status.getVoucherId(), ticketId);
            result.put("queuePosition", position);
            result.put("message", "排队中，前方还有 " + position + " 人");
        } else if ("PROCESSING".equals(status.getStatus())) {
            result.put("message", "正在处理中");
        } else if ("SUCCESS".equals(status.getStatus())) {
            result.put("orderId", status.getOrderId());
            result.put("message", "恭喜！抢购成功");
        } else if ("FAILED".equals(status.getStatus())) {
            result.put("reason", status.getFailReason());
            result.put("message", "抢购失败：" + status.getFailReason());
        }

        return Result.ok(result);
    }

    /**
     * 获取排队位置
     */
    private int getQueuePosition(Long voucherId, String ticketId) {
        ConcurrentLinkedQueue<QueueRequest> queue = queueMap.get(voucherId);
        if (queue == null) return 0;

        int position = 0;
        for (QueueRequest req : queue) {
            if (req.getTicketId().equals(ticketId)) {
                return position;
            }
            position++;
        }
        return position;
    }

    /**
     * 消费队列（后台线程）
     * <p>
     * 【关键】消费时使用 DB 乐观锁，保证不超卖
     */
    private void consumeQueue() {
        while (running) {
            try {
                for (Map.Entry<Long, ConcurrentLinkedQueue<QueueRequest>> entry : queueMap.entrySet()) {
                    Long voucherId = entry.getKey();
                    ConcurrentLinkedQueue<QueueRequest> queue = entry.getValue();

                    QueueRequest request = queue.poll();
                    if (request != null) {
                        // 减少排队人数统计
                        AtomicLong queueSize = queueSizeMap.get(voucherId);
                        if (queueSize != null) {
                            queueSize.decrementAndGet();
                        }

                        processRequest(request);
                    }
                }

                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("【排队消费】异常", e);
            }
        }
    }

    /**
     * 处理单个排队请求
     * <p>
     * 【关键】使用 DB 乐观锁扣库存，保证不超卖
     */
    private void processRequest(QueueRequest request) {
        String ticketId = request.getTicketId();
        QueueStatus status = statusMap.get(ticketId);

        if (status == null) {
            return;
        }

        try {
            status.setStatus("PROCESSING");

            // 1. 生成订单 ID
            Long orderId = degradationService.generateOrderId();

            // 2. 【关键】检查一人一单（DB 查询）
            boolean hasPurchased = degradationService.hasUserPurchased(request.getUserId(), request.getVoucherId());
            if (hasPurchased) {
                markFailed(status, "您已购买过该优惠券");
                return;
            }

            // 3. 【关键】乐观锁扣库存（DB 原子操作，保证不超卖）
            boolean deductSuccess = degradationService.deductStock(request.getVoucherId());
            if (!deductSuccess) {
                // 库存扣减失败 = 被其他人抢光了
                markFailed(status, "很遗憾，商品已被抢光");
                totalSoldOut.incrementAndGet();
                return;
            }

            // 4. 创建订单（记录到 purchaseRecordLog，用于 Redis 恢复同步）
            VoucherOrder order = degradationService.createOrder(orderId, request.getUserId(), request.getVoucherId());

            // 5. 标记成功
            status.setStatus("SUCCESS");
            status.setOrderId(orderId.toString());
            status.setCompleteTime(System.currentTimeMillis());

            totalProcessed.incrementAndGet();

            log.info("【排队处理】成功: ticketId={}, orderId={}, userId={}, voucherId={}",
                    ticketId, orderId, request.getUserId(), request.getVoucherId());

        } catch (Exception e) {
            log.error("【排队处理】失败: ticketId={}", ticketId, e);
            markFailed(status, "系统异常，请稍后重试");
        }
    }

    private void markFailed(QueueStatus status, String reason) {
        status.setStatus("FAILED");
        status.setFailReason(reason);
        status.setCompleteTime(System.currentTimeMillis());
        totalProcessed.incrementAndGet();
    }

    /**
     * Redis 恢复后清空队列
     */
    public void clearAllQueues() {
        queueMap.clear();
        queueSizeMap.clear();
        userQueueMap.clear();
        log.info("【排队服务】队列已清空");
    }

    /**
     * 获取排队统计
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("totalQueued", totalQueued.get());
        stats.put("totalProcessed", totalProcessed.get());
        stats.put("totalRejected", totalRejected.get());
        stats.put("totalSoldOut", totalSoldOut.get());
        stats.put("currentQueueSize", totalQueued.get() - totalProcessed.get());
        stats.put("activeVouchers", queueMap.size());
        return stats;
    }
}