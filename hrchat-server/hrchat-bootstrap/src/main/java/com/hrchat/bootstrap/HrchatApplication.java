package com.hrchat.bootstrap;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * HR智能问数 服务启动器。
 *
 * <p>模块化单体（ADR-002）：启动类仅承担装配职责，业务代码按模块分包扫描。
 * {@code @MapperScan} 仅注册标注 {@code @Mapper} 的 MyBatis-Plus Mapper 接口。</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.hrchat")
@MapperScan(basePackages = "com.hrchat", annotationClass = Mapper.class)
public class HrchatApplication {

    /**
     * 主入口。
     *
     * @param args 启动参数（--spring.profiles.active=local 为本地轻量环境）
     */
    public static void main(String[] args) {
        SpringApplication.run(HrchatApplication.class, args);
    }
}
