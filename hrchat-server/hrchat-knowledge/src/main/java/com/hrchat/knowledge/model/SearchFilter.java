package com.hrchat.knowledge.model;

/**
 * 检索前置标量过滤条件（架构红线：权限在 Java 侧裁决后以过滤条件下发，Milvus 侧不持有业务权限）。
 *
 * @param domain  主题域（分区键），null 表示不过滤
 * @param type    对象类型，null 表示不过滤
 * @param status  状态，null 表示不过滤
 * @param version 语义层版本号，null 表示不过滤
 */
public record SearchFilter(String domain, String type, String status, Integer version) {

    public static SearchFilter of(String domain, String type, String status, Integer version) {
        return new SearchFilter(domain, type, status, version);
    }
}
