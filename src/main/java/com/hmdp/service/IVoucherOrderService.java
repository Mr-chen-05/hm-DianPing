package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 添加购买秒杀券订单
     * @param voucherId
     * @return
     */
    Result seckillVoucher(Long voucherId) throws InterruptedException;

    void createVoucherOrder(VoucherOrder voucherOrder);

    // Result createVoucherOrder(Long voucherId) throws InterruptedException;
}
