package com.hrchat.authz.identity;

/**
 * 当前请求身份来源抽象。
 *
 * <p>Demo 由受限的 mock 请求头提供身份；正式环境将由已验签的 OIDC/JWT 提供身份。</p>
 */
public interface AuthenticatedIdentityProvider {

    /**
     * 将当前认证模式提供的外部身份解析为业务工号。
     *
     * @param presentedUserNo Demo 请求头中的工号，可为空
     * @return 非空业务工号
     */
    String resolveUserNo(String presentedUserNo);

    /** 当前认证模式，用于审计和诊断。 */
    String mode();
}
