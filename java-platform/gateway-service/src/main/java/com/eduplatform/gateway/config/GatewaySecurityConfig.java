package com.eduplatform.gateway.config;

import com.eduplatform.shared.api.ApiError;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashSet;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
/**
 * 定义浏览器统一入口的 JWT 校验、角色路由和 CORS 边界。
 *
 * <p>公开课程与登录端点允许匿名访问；教师写接口必须具备 TEACHER 角色，其余请求至少要求
 * 有效访问令牌。认证和权限失败统一返回共享错误信封，不把内部异常暴露给浏览器。</p>
 */
public class GatewaySecurityConfig {
    @Bean
    /** 校验本地 HMAC 密钥最低强度并创建仅供 JWT 编解码使用的密钥对象。 */
    SecretKey gatewayJwtSecretKey(GatewayProperties properties) {
        String secret = properties.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("IAM_JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    /** 创建同时校验签发者、受众、有效期和签名的响应式 JWT 解码器。 */
    ReactiveJwtDecoder gatewayJwtDecoder(SecretKey key, GatewayProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
                new JwtAudienceValidator(properties.jwt().audience()));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    /** 构建路由级访问规则；拒绝请求不会进入任何领域服务。 */
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ObjectMapper objectMapper) {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            LinkedHashSet<SimpleGrantedAuthority> combined = new LinkedHashSet<>();
            authorities.convert(jwt).forEach(value -> combined.add(new SimpleGrantedAuthority(value.getAuthority())));
            List<String> permissions = jwt.getClaimAsStringList("permissions");
            if (permissions != null) permissions.forEach(value -> combined.add(new SimpleGrantedAuthority(value)));
            return Flux.fromIterable(combined);
        });
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                                "/api/v1/auth/registration-challenges", "/api/v1/auth/register",
                                "/api/v1/auth/password-reset-challenges", "/api/v1/auth/password-reset",
                                "/api/v1/auth/verification-login-challenges", "/api/v1/auth/verification-login",
                                "/api/v1/auth/captchas", "/api/v1/auth/captchas/*/refresh").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/auth/channel-capabilities",
                                "/api/v1/protocols/current").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/courses/*/lessons/*/content")
                        .hasAuthority("learning:access")
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/courses/*/lessons/*/resources/*/playback-grants",
                                "/api/v1/courses/*/lessons/*/resources/*/access-grants")
                        .hasAuthority("learning:access")
                        .pathMatchers(HttpMethod.GET, "/api/v1/courses", "/api/v1/courses/**").permitAll()
                        .pathMatchers("/api/v1/teacher/courses", "/api/v1/teacher/courses/**").hasRole("TEACHER")
                        .pathMatchers("/api/v1/admin/knowledge-points", "/api/v1/admin/knowledge-points/**").hasAuthority("administration:access")
                        .pathMatchers("/api/v1/admin/question-imports/**",
                                "/api/v1/admin/legacy-question-imports", "/api/v1/admin/legacy-question-imports/**")
                        .hasAuthority("administration:access")
                        .pathMatchers("/api/v1/teacher/questions", "/api/v1/teacher/questions/**",
                                "/api/v1/teacher/knowledge-points", "/api/v1/teacher/knowledge-points/**",
                                "/api/v1/teacher/question-imports", "/api/v1/teacher/question-imports/**").hasRole("TEACHER")
                        .pathMatchers("/api/v1/teacher/assessments", "/api/v1/teacher/assessments/**",
                                "/api/v1/teacher/attempts/**", "/api/v1/teacher/learning/**",
                                "/api/v1/teacher/gradebook", "/api/v1/teacher/gradebook/export")
                        .hasAuthority("teaching:access")
                        .pathMatchers("/api/v1/learning/**").hasAuthority("learning:access")
                        .pathMatchers("/api/v1/ai/**").hasAuthority("learning:access")
                        .pathMatchers("/api/v1/admin/users", "/api/v1/admin/users/**").hasAuthority("administration:access")
                        .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((exchange, exception) -> writeError(exchange, objectMapper,
                                HttpStatus.UNAUTHORIZED, "IAM.UNAUTHORIZED", "请先登录或重新登录")))
                .exceptionHandling(errors -> errors.accessDeniedHandler((exchange, exception) ->
                        writeError(exchange, objectMapper, HttpStatus.FORBIDDEN,
                                "IAM.FORBIDDEN", "无权访问该资源")))
                .build();
    }

    @Bean
    /** 仅允许配置中的前端源携带凭据，并暴露追踪与限流响应头。 */
    CorsWebFilter corsWebFilter(GatewayProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id"));
        // 成绩册下载需要读取安全文件名；只暴露响应头，不放宽来源、Cookie 或教学权限。
        configuration.setExposedHeaders(List.of("X-Trace-Id", "Retry-After", "Content-Disposition", "X-Agent-Run-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return new CorsWebFilter(source);
    }

    private static Mono<Void> writeError(ServerWebExchange exchange, ObjectMapper objectMapper,
            HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String traceId = exchange.getAttribute(TraceWebFilter.ATTRIBUTE);
        ApiError error = new ApiError(code, message, traceId, Instant.now(),
                exchange.getRequest().getPath().value(), List.of());
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(error);
        } catch (Exception serializationFailure) {
            bytes = "{\"code\":\"COMMON.INTERNAL_ERROR\",\"message\":\"服务暂时不可用\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
