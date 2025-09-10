package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.baomidou.mybatisplus.extension.toolkit.Db.save;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    // shift + f6 同时修改变量
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();// 创建线程池，单线程
    private volatile boolean running = true; // 控制线程运行状态

    //@PostConstruct 类初始化完后执行
    @PostConstruct
    private void init() {
        // 检查Redisson客户端是否正常
        try {
            redissonClient.getNodesGroup().pingAll();
            log.info("Redisson客户端连接正常");
        } catch (Exception e) {
            log.error("Redisson客户端连接异常", e);
        }
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //@PreDestroy 应用关闭前执行
    @PreDestroy
    private void destroy() {
        log.info("开始关闭秒杀订单处理服务...");
        running = false; // 停止线程循环
        SECKILL_ORDER_EXECUTOR.shutdown(); // 关闭线程池
        try {
            // 等待线程池关闭，最多等待5秒
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("线程池未能在5秒内正常关闭，强制关闭");
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            } else {
                log.info("秒杀订单处理服务已优雅关闭");
            }
        } catch (InterruptedException e) {
            log.warn("等待线程池关闭时被中断");
            SECKILL_ORDER_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            /*
                        这是一个双重安全检查的条件表达式，确保线程能够优雅退出：
            1. running 标志位：
            这是我们自定义的volatile布尔变量

            初始值为true，表示线程应该继续运行

            当应用关闭时，@PreDestroy方法会将其设置为false

            volatile关键字确保多线程间的可见性

            2. !Thread.currentThread().isInterrupted() 中断检查：

            Thread.currentThread() 获取当前执行的线程
            isInterrupted() 检查线程是否被中断

            ! 表示取反，即"没有被中断"

            当线程池调用shutdownNow()时，会中断正在运行的线程
            * */
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 streams.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断消息获取是否成功
                    if (CollectionUtils.isEmpty(list)) {
                        // 2.1如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3.解析消息中的订单信息
                    /*
                    * MapRecord<String, Object, Object> 可以分解为三个部分：

MapRecord：这是 Spring Data Redis 中表示 Stream 中一条记录（或消息） 的专用类。它包含了这条消息的所有元数据和内容。

<String>：这第一个泛型参数代表 Stream 的 Key 的数据类型。在你的例子中，queueName 是一个 String 类型的变量（例如 "stream.orders"），所以这里是 String。

<Object, Object>：这两个泛型参数代表 消息体（即 Value）中字段（Field）和值（Value）的数据类型。

第一个 Object：字段（Field）的类型，通常是 String（例如 "voucherId", "userId"）。

第二个 Object：字段值（Value）的类型，可以是 String, Long, Integer 等。*/
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    // 使用Hutool将Map数据转换为VoucherOrder对象
                    // 参数false表示：严格模式，如果Map中存在VoucherOrder类没有的属性，将抛出异常
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3.如果获取成功，可以下单
                    handlerVoucherOrder(voucherOrder);
                    // 4.ACK确认 告诉队列消息被处理了 SACK stream.orders g1 id  record.getId()获取队列里的消息id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    if (running && !Thread.currentThread().isInterrupted()) {
                        handlePendingList();
                    }
                }

            }
        }

        private void handlePendingList() {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    // 1.获取pending-list队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 streams.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断消息获取是否成功
                    if (CollectionUtils.isEmpty(list)) {
                        // 2.1如果获取失败，说明peding-list没有异常消息，结束下一次循环
                        break;
                    }
                    // 3.解析消息中的订单信息
                    /*
                    * MapRecord<String, Object, Object> 可以分解为三个部分：

MapRecord：这是 Spring Data Redis 中表示 Stream 中一条记录（或消息） 的专用类。它包含了这条消息的所有元数据和内容。

<String>：这第一个泛型参数代表 Stream 的 Key 的数据类型。在你的例子中，queueName 是一个 String 类型的变量（例如 "stream.orders"），所以这里是 String。

<Object, Object>：这两个泛型参数代表 消息体（即 Value）中字段（Field）和值（Value）的数据类型。

第一个 Object：字段（Field）的类型，通常是 String（例如 "voucherId", "userId"）。

第二个 Object：字段值（Value）的类型，可以是 String, Long, Integer 等。*/
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    // 使用Hutool将Map数据转换为VoucherOrder对象
                    // 参数false表示：严格模式，如果Map中存在VoucherOrder类没有的属性，将抛出异常
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3.如果获取成功，可以下单
                    handlerVoucherOrder(voucherOrder);
                    // 4.ACK确认 告诉队列消息被处理了 SACK stream.orders g1 id  record.getId()获取队列里的消息id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理peding-list异常", e);
                    if (!running || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }

//     阻塞队列的例子
/*    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 1.获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handlerVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }

            }
        }*/
    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断获取锁是否成功
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();
        // 5.1 查询用户是否已经购买过优惠券
        long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2 判断是否存在
        if (count > 0) {
            // 用户已经购买过了，不能再次购买
            log.error("用户已经购买过一次了");
            return;
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // 🔥使用数据库当前值
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)// 乐观锁 更新库存的时候判断库存是否充足
                .update();
        if (!success) {
            // 扣减失败
            log.error("扣减库存失败");
            return;
        }
        // 7.4保存订单
        save(voucherOrder);

    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString(),
                String.valueOf(orderId),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        );

        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.2不为0，代表没有购买资格
            String error = switch (r) {
                case -1 -> "秒杀尚未开始";
                case -2 -> "秒杀已经结束";
                case 1 -> "库存不足";
                case 2 -> "不能重复下单";
                default -> throw new IllegalStateException("秒杀返回异常: " + r);
            };
            return Result.fail(error);
        }
        // 3.获取代理对象
        // 获取代理对象(事务)
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }
    /*@Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString(),
                LocalDateTime.now().toString()
        );

        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0) {
            // 2.2不为0，代表没有购买资格
            String error = switch (r) {
                case -1 -> "秒杀尚未开始";
                case -2 -> "秒杀已经结束";
                case 1 -> "库存不足";
                case 2 -> "不能重复下单";
                default -> throw new IllegalStateException("秒杀返回异常: " + r);
            };
            return Result.fail(error);
        }
        // 2.3为0，代表有购买资格，把下单芯线保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        // 2.4创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.5订单id
        voucherOrder.setId(orderId);
        // 2.6用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        // 2.7代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.8放入阻塞队列
        orderTasks.add(voucherOrder);

        // 3.获取代理对象
        // 获取代理对象(事务)
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }*/


/*
    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        // 下面这样设计的原因是必须得等事务提交完，才能释放锁，不然会出现并发问题（注：在单体服务里没有问题，但是在集群模式下还是会有并发安全问题
       // 。`synchronized` 只能保证单个 JVM 内的线程安全，无法解决分布式环境下的并发问题，需要使用分布式锁来保证跨 JVM 的数据一致性。
       // 因为 `synchronized` 锁是基于 JVM 内存的本地锁，不同 JVM 实例之间无法共享锁状态，所以无法在分布式环境下协调多个节点的并发访问。
       // ）
       //  synchronized (userId.toString().intern()) {
       //      // 先获取代理对象,为什么？因为如果使用this，那么获取的对象是当前对象VoucherOrderServiceImpl
       //      // ,而不是代理对象,事务会失效,没有事务功能，那这是为什么呢?为什么要获取代理对象，是因为要使用的是@Transactional这个注解启用事务，
       //      // 因为如果要想事务生效，spring对象对当前类做了动态代理，拿到了它的代理对象，用它(代理对象)来做的事务处理
       //      // 而动态代理负责维护事务的生命周期，包括事务的开启、提交或回滚。
       //
       //      // 获取代理对象(代理)
       //      IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
       //      return proxy.createVoucherOrder(voucherId);
       //  }

        // 集群模型下要用分布式锁
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断获取锁是否成功
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            return Result.fail("一人只允许下一单,禁止重复下单！");
        }
        try {
            // 获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }

    }
*/

   /* @Transactional(rollbackFor = Exception.class)
    public Result createVoucherOrder(Long voucherId) throws InterruptedException {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();
        // 5.1 查询用户是否已经购买过优惠券
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // Thread.sleep(2000);  // 模拟增加延时，扩大并发窗口
        // 5.2 判断是否存在
        if (count > 0) {
            // 用户已经购买过了，不能再次购买
            return Result.fail("用户不能重复下单");
        }
        // 模拟延时
        // Thread.sleep(9000);
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // 🔥使用数据库当前值
                .eq("voucher_id", voucherId)
                .gt("stock", 0)// 乐观锁 更新库存的时候判断库存是否充足
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足");
        }
        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        // 7.3代金券id
        voucherOrder.setVoucherId(voucherId);
        // 7.4保存订单
        save(voucherOrder);
        // 8.返回订单id
        return Result.ok(voucherOrder.getId());

    }*/
}