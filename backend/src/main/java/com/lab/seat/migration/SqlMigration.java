package com.lab.seat.migration;

import java.util.List;

/**
 * 一个已加载的迁移脚本。
 *
 * @param version    版本号字符串，按整数比较（"2" 在 "10" 之前）
 * @param description 从文件名解析出的描述
 * @param checksum   脚本全文的 SHA-256，用于检测已应用脚本被事后修改
 * @param statements 已按分号切分、已剥离注释的语句列表
 */
record SqlMigration(String version, String description, String checksum, List<String> statements) {}
