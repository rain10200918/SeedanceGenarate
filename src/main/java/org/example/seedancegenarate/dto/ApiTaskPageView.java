package org.example.seedancegenarate.dto;

import java.util.List;

/** 保留原分页业务字段，不公开分页框架内部选项。 */
public record ApiTaskPageView(List<ApiTaskView> records, long total, long size, long current, long pages) {
}
