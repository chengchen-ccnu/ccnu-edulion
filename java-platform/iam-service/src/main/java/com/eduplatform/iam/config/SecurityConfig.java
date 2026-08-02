package com.eduplatform.iam.config;

import com.eduplatform.shared.api.ApiError;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(IamProperties.class)
/**
 * IAM 服务的密码哈希、JWT 编解码和无状态 HTTP 访问边界。
 * 登录/刷新/退出公开，其他接口必须持有效 JWT；认证失败使用统一错误信封。
 */
public class SecurityConfig {
    @Bean
    /** 使用固定成本 BCrypt 哈希账号密码；原始密码不写入数据库或日志。 */
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    /** 校验 HMAC 密钥最低 32 字节，配置不足时阻止 IAM 启动。 */
    SecretKey jwtSecretKey(IamProperties properties) {
        String secret = properties.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("IAM_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    /** 创建同时校验签发者、受众、签名和时间声明的 JWT 解码器。 */
    NimbusJwtDecoder jwtDecoder(SecretKey key, IamProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
                new JwtAudienceValidator(properties.jwt().audience()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    /** 构建无服务器 Session 的安全过滤链；状态只来自 JWT 和刷新 Cookie。 */
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**", "/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            String traceId = (String) request.getAttribute(TraceFilter.ATTRIBUTE);
                            objectMapper.writeValue(response.getOutputStream(), new ApiError(
                                    "IAM.UNAUTHORIZED", "请先登录或重新登录", traceId, Instant.now(),
                                    request.getRequestURI(), List.of()));
                        }))
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    String traceId = (String) request.getAttribute(TraceFilter.ATTRIBUTE);
                    objectMapper.writeValue(response.getOutputStream(), new ApiError(
                            "IAM.FORBIDDEN", "无权访问该资源", traceId, Instant.now(),
                            request.getRequestURI(), List.of()));
                }));
        return http.build();
    }
}
