package org.pgledger.admin.passkey;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the approval check in front of every handler of the admin web (it exists in console mode only). */
@Configuration(proxyBeanMethods = false)
public class PasskeyWebConfiguration implements WebMvcConfigurer {

    private final ApprovalInterceptor approvals;

    public PasskeyWebConfiguration(ApprovalInterceptor approvals) {
        this.approvals = approvals;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(approvals);
    }
}
