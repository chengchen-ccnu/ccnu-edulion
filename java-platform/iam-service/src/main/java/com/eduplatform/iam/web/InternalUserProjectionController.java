package com.eduplatform.iam.web;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.config.TraceFilter;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.service.AuthException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Learning 专用 IAM 内部投影接口；只返回批量昵称，服务令牌和调用方均需匹配。 */
@RestController
@RequestMapping("/internal/v1/users")
public class InternalUserProjectionController {
    private static final String EXPECTED_CALLER = "learning-service";

    private final IamMapper mapper;
    private final IamProperties properties;

    public InternalUserProjectionController(IamMapper mapper, IamProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 批量读取昵称；缺失用户不出现在响应中，调用方可据此安全降级为编号。 */
    @PostMapping("/nicknames")
    public NicknameResponse nicknames(@RequestHeader("X-Internal-Caller") String caller,
            @RequestHeader("X-Internal-Service-Token") String token,
            @Valid @RequestBody NicknameRequest request) {
        if (!EXPECTED_CALLER.equals(caller) || !matches(properties.internal().serviceToken(), token)) {
            throw new AuthException(HttpStatus.FORBIDDEN, "IAM.INTERNAL_CALLER_FORBIDDEN", "内部调用方未授权");
        }
        List<String> ids = request.learnerIds().stream().map(UUID::toString).distinct().toList();
        Map<String, String> values = new LinkedHashMap<>();
        for (IamMapper.NicknameRow row : mapper.findDisplayNames(ids)) {
            if (row.id() != null && row.displayName() != null) values.put(row.id(), row.displayName());
        }
        return new NicknameResponse(values);
    }

    private static boolean matches(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    public record NicknameRequest(@NotNull @Size(min = 1, max = 5000) List<UUID> learnerIds) {}
    public record NicknameResponse(Map<String, String> nicknames) {}
}
