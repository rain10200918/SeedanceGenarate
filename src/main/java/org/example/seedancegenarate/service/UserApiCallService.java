package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.dto.*;
import org.example.seedancegenarate.mapper.ApiCallLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.example.seedancegenarate.exception.BusinessException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserApiCallService {
    private final ApiCallLogMapper mapper;

    public Page<UserApiCallView> page(long ownerId, long current, long size, UserApiCallQuery query) {
        validate(ownerId, query);
        if (current < 1 || size < 1 || size > 100 || current - 1 > Long.MAX_VALUE / size) {
            throw BusinessException.badRequest("分页参数不合法");
        }
        LocalDateTime from = time(query.from()), to = time(query.to());
        var page = new Page<UserApiCallView>(current, size, mapper.countUserCalls(ownerId, query, from, to));
        page.setRecords(mapper.selectUserCalls(ownerId, query, from, to, (current - 1) * size, size));
        return page;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserApiCallSummary summary(long ownerId, UserApiCallQuery query) {
        validate(ownerId, query);
        LocalDateTime from = time(query.from()), to = time(query.to());
        var totals = mapper.selectUserTotals(ownerId, query, from, to);
        return new UserApiCallSummary(number(totals,"total").longValue(), number(totals,"received").longValue(),
                number(totals,"success").longValue(), number(totals,"failed").longValue(),
                number(totals,"rejected").longValue(), new BigDecimal(number(totals,"totalCost").toString()),
                "CNY", mapper.selectUserErrorCounts(ownerId, query, from, to));
    }

    private void validate(long ownerId, UserApiCallQuery query) {
        if (ownerId <= 0) throw BusinessException.unauthorized("请先登录");
        if (query == null || query.apiKeyId() != null && query.apiKeyId() <= 0) {
            throw BusinessException.badRequest("查询参数不合法");
        }
        text(query.model(),64); text(query.provider(),32); text(query.errorCode(),32);
        if (query.status() != null && !Set.of("RECEIVED","SUCCESS","FAILED","REJECTED").contains(query.status())) {
            throw BusinessException.badRequest("状态不合法");
        }
        LocalDateTime from = time(query.from()), to = time(query.to());
        if (from != null && to != null && !from.isBefore(to)) throw BusinessException.badRequest("时间范围不合法");
    }

    private void text(String value, int max) {
        if (value != null && (value.isBlank() || value.length() > max || value.chars().anyMatch(Character::isISOControl))) {
            throw BusinessException.badRequest("筛选参数不合法");
        }
    }

    private LocalDateTime time(String value) {
        if (value == null) return null;
        try {
            if (value.length() > 29) throw BusinessException.badRequest("时间格式不合法");
            LocalDateTime parsed = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (parsed.getYear() < 1000 || parsed.getYear() > 9999) throw BusinessException.badRequest("时间超出范围");
            return parsed;
        } catch (DateTimeParseException failure) {
            throw BusinessException.badRequest("时间格式不合法");
        }
    }

    private Number number(Map<String,Object> row, String field) {
        // JDBC drivers differ in alias casing (MySQL preserves it, H2 uppercases it).
        return row.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(field))
                .map(e -> (Number)e.getValue()).findFirst().orElseThrow();
    }
}
