package com.eduplatform.iam.service;

import com.eduplatform.iam.domain.ProtocolDocument;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.web.AccountLifecycleDtos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 查询当前有效协议，并在注册事务前验证客户端同意的不可变版本。 */
@Service
public class ProtocolDocumentService {
    private static final Set<String> REQUIRED_TYPES = Set.of("TERMS", "PRIVACY");
    private final IamMapper mapper;

    public ProtocolDocumentService(IamMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @return 当前已生效且唯一的 TERMS、PRIVACY 正文；缺失或配置重复视为服务不可用，
     * 避免注册在没有法律文本事实时继续。
     */
    public List<ProtocolDocument> current() {
        List<ProtocolDocument> documents = mapper.listCurrentProtocolDocuments();
        Map<String, Long> counts = documents.stream().collect(Collectors.groupingBy(
                ProtocolDocument::getDocumentType, Collectors.counting()));
        if (!counts.keySet().equals(REQUIRED_TYPES) || counts.values().stream().anyMatch(value -> value != 1L)) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "IAM.PROTOCOLS_UNAVAILABLE",
                    "当前协议暂时不可用，请稍后重试", 60L);
        }
        return List.copyOf(documents);
    }

    /**
     * 验证注册恰好同意两个当前版本；旧版本、未来版本、重复类型均返回 422，不自动替用户同意。
     * @param agreements 客户端明确提交的协议类型和版本
     */
    public void requireCurrentAgreements(List<AccountLifecycleDtos.Agreement> agreements) {
        Map<String, AccountLifecycleDtos.Agreement> submitted;
        try {
            submitted = agreements.stream().collect(Collectors.toMap(
                    agreement -> agreement.documentType().toUpperCase(java.util.Locale.ROOT),
                    Function.identity()));
        } catch (IllegalStateException duplicate) {
            throw invalidAgreement();
        }
        Map<String, ProtocolDocument> current = current().stream().collect(Collectors.toMap(
                ProtocolDocument::getDocumentType, Function.identity()));
        if (!submitted.keySet().equals(REQUIRED_TYPES) || REQUIRED_TYPES.stream().anyMatch(type ->
                !current.get(type).getDocumentVersion().equals(submitted.get(type).version()))) {
            throw invalidAgreement();
        }
    }

    private static AuthException invalidAgreement() {
        return new AuthException(HttpStatus.UNPROCESSABLE_CONTENT, "IAM.PROTOCOL_VERSION_INVALID",
                "必须分别同意当前版本的用户协议和隐私政策");
    }
}
