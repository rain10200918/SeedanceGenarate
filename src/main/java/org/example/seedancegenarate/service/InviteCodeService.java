package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.seedancegenarate.entity.InviteCode;

public interface InviteCodeService extends IService<InviteCode> {
    InviteCode generate(Long adminUserId);

    void consume(String code, Long userId);

    Page<InviteCode> pageCodes(Long current, Long size);
}
