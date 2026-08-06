package org.example.seedancegenarate.service.Impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.seedancegenarate.entity.InviteCode;
import org.example.seedancegenarate.mapper.InviteCodeMapper;
import org.example.seedancegenarate.service.InviteCodeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
public class InviteCodeServiceImpl extends ServiceImpl<InviteCodeMapper, InviteCode> implements InviteCodeService {
    private static final String UNUSED = "UNUSED";
    private static final String USED = "USED";
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public InviteCode generate(Long adminUserId) {
        InviteCode inviteCode = new InviteCode();
        inviteCode.setCode(nextCode());
        inviteCode.setStatus(UNUSED);
        inviteCode.setCreatedBy(adminUserId);
        this.save(inviteCode);
        return inviteCode;
    }

    @Override
    @Transactional
    public void consume(String code, Long userId) {
        if (!StringUtils.hasText(code)) {
            throw new RuntimeException("请输入邀请码");
        }
        LambdaUpdateWrapper<InviteCode> wrapper = Wrappers.<InviteCode>lambdaUpdate()
                .eq(InviteCode::getCode, code.trim())
                .eq(InviteCode::getStatus, UNUSED)
                .set(InviteCode::getStatus, USED)
                .set(InviteCode::getUsedBy, userId)
                .set(InviteCode::getUsedTime, LocalDateTime.now());
        boolean updated = this.update(wrapper);
        if (!updated) {
            throw new RuntimeException("邀请码无效或已使用");
        }
    }

    @Override
    public Page<InviteCode> pageCodes(Long current, Long size) {
        long pageCurrent = Math.max(current == null ? 1L : current, 1L);
        long pageSize = Math.min(Math.max(size == null ? 10L : size, 1L), 100L);
        return this.page(
                new Page<>(pageCurrent, pageSize),
                Wrappers.<InviteCode>lambdaQuery()
                        .orderByDesc(InviteCode::getCreateTime)
                        .orderByDesc(InviteCode::getId)
        );
    }

    private String nextCode() {
        for (int i = 0; i < 5; i++) {
            String code = randomCode();
            long count = this.count(Wrappers.<InviteCode>lambdaQuery().eq(InviteCode::getCode, code));
            if (count == 0) {
                return code;
            }
        }
        throw new RuntimeException("邀请码生成失败，请重试");
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder("SD-");
        for (int i = 0; i < 10; i++) {
            builder.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
