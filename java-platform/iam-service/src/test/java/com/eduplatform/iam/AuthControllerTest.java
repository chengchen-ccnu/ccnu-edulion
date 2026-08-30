package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.service.AuthService;
import com.eduplatform.iam.service.TokenPair;
import com.eduplatform.iam.web.AuthController;
import com.eduplatform.iam.web.LoginRequest;
import com.eduplatform.iam.web.LoginResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthControllerTest {
    private final AuthService authService = mock(AuthService.class);
    private final IamProperties properties = new IamProperties(
            new IamProperties.Jwt("issuer", "audience", "secret", Duration.ofMinutes(15)),
            new IamProperties.Refresh(Duration.ofDays(7), false),
            new IamProperties.LoginRateLimit(5, Duration.ofMinutes(15)),
            new IamProperties.Verification(Duration.ofMinutes(10), Duration.ofMinutes(1), 5,
                    "fixed-local", "246810", "test-only-verification-hmac-secret-32-bytes", ""),
            new IamProperties.Captcha(Duration.ofMinutes(2), 5, 3),
            new IamProperties.SessionCache("edu:iam:session-version", Duration.ofMinutes(20)),
            new IamProperties.Media("http://127.0.0.1:8083", Duration.ofSeconds(5)),
            new IamProperties.Seed(false, ""));
    private final AuthController controller = new AuthController(authService, properties);

    @Test
    void loginReturnsAccessTokenAndHttpOnlyRefreshCookie() {
        when(authService.login("student_test_01", "correct-password", "127.0.0.1", null))
                .thenReturn(new TokenPair("access-token", 900, "opaque-refresh"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        ResponseEntity<LoginResponse> response = controller.login(
                new LoginRequest("student_test_01", "correct-password"), request);

        assertThat(response.getBody()).extracting(LoginResponse::accessToken, LoginResponse::expiresIn)
                .containsExactly("access-token", 900L);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .contains("EDU_REFRESH=opaque-refresh", "HttpOnly", "SameSite=Strict", "Path=/api/v1/auth")
                .doesNotContain("Secure");
    }

    @Test
    void logoutRevokesTokenAndClearsCookie() {
        ResponseEntity<Void> response = controller.logout("opaque-refresh");
        verify(authService).logout("opaque-refresh");
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0", "EDU_REFRESH=");
    }
}
