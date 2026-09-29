package com.hrchat.authz.model;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入当前用户权限上下文到 Controller 方法参数。
 *
 * <p>认证委托 SSO（V-07），本地以请求头 {@code X-User-No} 模拟。</p>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
