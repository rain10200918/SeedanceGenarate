package org.example.seedancegenarate.dto;

/**
 * 「我这个账号能同时跑几个，已经分出去多少」—— 用户要分配份额，得先看得到这两个数。
 *
 * @param accountLimit 账号总量；<b>null = 不限制</b>（个人用户的常态）
 * @param allocated    已经分配给各把 key 的份额之和（允许超过总量：各把不会同时跑满）
 * @param shadow       系统处于试运行：超出只记录、不真的拦
 */
public record ApiKeyQuotaView(Integer accountLimit, int allocated, boolean shadow) {
}
