package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.persistence.IamMapper;
import com.eduplatform.iam.service.AuthException;
import com.eduplatform.iam.web.InternalUserProjectionController;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InternalUserProjectionControllerTest {
    private static final String SERVICE_TOKEN = "test-learning-service-token";
    private final IamMapper mapper = mock(IamMapper.class);
    private final InternalUserProjectionController controller = new InternalUserProjectionController(mapper,
            new IamProperties(null, null, null, null, null, null, null, null,
                    new IamProperties.Internal(SERVICE_TOKEN)));

    @Test
    void batchNicknameProjectionUsesStableIdsAndDeduplicatesRequest() {
        UUID learnerId = UUID.randomUUID();
        String learnerIdText = learnerId.toString();
        when(mapper.findDisplayNames(List.of(learnerIdText)))
                .thenReturn(List.of(new IamMapper.NicknameRow(learnerIdText, "student_test_01")));

        InternalUserProjectionController.NicknameResponse response = controller.nicknames(
                "learning-service", SERVICE_TOKEN,
                new InternalUserProjectionController.NicknameRequest(List.of(learnerId, learnerId)));

        assertThat(response.nicknames()).containsEntry(learnerIdText, "student_test_01");
        verify(mapper).findDisplayNames(List.of(learnerIdText));
    }

    @Test
    void wrongInternalTokenIsForbiddenBeforeDatabaseLookup() {
        UUID learnerId = UUID.randomUUID();

        assertThatThrownBy(() -> controller.nicknames("learning-service", "wrong-token",
                new InternalUserProjectionController.NicknameRequest(List.of(learnerId))))
                .isInstanceOfSatisfying(AuthException.class,
                        error -> assertThat(error.status().value()).isEqualTo(403));
        verify(mapper, org.mockito.Mockito.never()).findDisplayNames(org.mockito.ArgumentMatchers.anyList());
    }
}
