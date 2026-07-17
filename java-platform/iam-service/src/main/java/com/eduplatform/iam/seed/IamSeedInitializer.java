package com.eduplatform.iam.seed;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.persistence.IamMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile({"local", "test"})
@ConditionalOnProperty(prefix = "platform.iam.seed", name = "enabled", havingValue = "true")
public class IamSeedInitializer implements ApplicationRunner {
    private static final SeedUser[] USERS = {
        new SeedUser("00000000-0000-4000-8000-000000000101", "student_test_01", "测试学生", "STUDENT", "learning:access"),
        new SeedUser("00000000-0000-4000-8000-000000000102", "teacher_test_01", "测试教师", "TEACHER", "teaching:access"),
        new SeedUser("00000000-0000-4000-8000-000000000103", "admin_test_01", "测试管理员", "ADMIN", "administration:access")
    };

    private final IamMapper mapper;
    private final PasswordEncoder encoder;
    private final IamProperties properties;

    public IamSeedInitializer(IamMapper mapper, PasswordEncoder encoder, IamProperties properties) {
        this.mapper = mapper;
        this.encoder = encoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String password = properties.seed().password();
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("IAM_SEED_PASSWORD must contain at least 12 characters when seed is enabled");
        }
        for (SeedUser user : USERS) {
            mapper.upsertRole(user.role(), user.role());
            mapper.upsertPermission(user.permission(), user.permission());
            mapper.upsertSeedUser(user.id(), user.account(), user.displayName(), encoder.encode(password));
            mapper.assignRole(user.id(), user.role());
            mapper.assignPermission(user.role(), user.permission());
        }
    }

    private record SeedUser(String id, String account, String displayName, String role, String permission) {}
}
