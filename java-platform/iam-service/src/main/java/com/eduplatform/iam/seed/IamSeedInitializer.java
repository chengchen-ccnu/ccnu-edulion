package com.eduplatform.iam.seed;

import com.eduplatform.iam.config.IamProperties;
import com.eduplatform.iam.persistence.IamMapper;
import java.time.Instant;
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
/**
 * 仅在 local/test 且显式开启时幂等写入四个演示身份、角色、最小权限及测试协议正文。
 *
 * <p>密码来自未跟踪环境变量并在写库前哈希；配置不足会阻止启动。重复执行更新固定测试
 * 标识，不无限追加用户；生产 profile 不会装配本初始化器。</p>
 */
public class IamSeedInitializer implements ApplicationRunner {
    private static final SeedUser[] USERS = {
        new SeedUser("00000000-0000-4000-8000-000000000101", "seed_student_01", "测试学生", "STUDENT", "learning:access"),
        new SeedUser("00000000-0000-4000-8000-000000000102", "seed_teacher_01", "测试教师一", "TEACHER", "teaching:access"),
        new SeedUser("00000000-0000-4000-8000-000000000104", "seed_teacher_02", "测试教师二", "TEACHER", "teaching:access"),
        new SeedUser("00000000-0000-4000-8000-000000000103", "seed_admin_01", "测试管理员", "ADMIN", "administration:access")
    };

    private final IamMapper mapper;
    private final PasswordEncoder encoder;
    private final IamProperties properties;
    private final com.eduplatform.iam.security.IdentitySecurityStore security;
    private final com.eduplatform.iam.service.AdminAuthority authority;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public IamSeedInitializer(IamMapper mapper, PasswordEncoder encoder, IamProperties properties,
            com.eduplatform.iam.security.IdentitySecurityStore security,com.eduplatform.iam.service.AdminAuthority authority,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.encoder = encoder;
        this.properties = properties;
        this.security=security;this.authority=authority;this.jdbc=jdbc;
    }

    @Override
    /** 在单一事务中生成角色、权限、测试用户及关联，任一步失败则整体回滚。 */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void run(ApplicationArguments args) {
        String password = properties.seed().password();
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("IAM_SEED_PASSWORD must contain at least 12 characters when seed is enabled");
        }
        authority.lock();
        for (SeedUser user : USERS) {
            mapper.upsertRole(user.role(), user.role());
            mapper.upsertPermission(user.permission(), user.permission());
            var existing=mapper.findUserByIdForUpdate(user.id());
            boolean retainedTeacher=existing!=null && mapper.findRoles(user.id()).contains("TEACHER");
            if(existing!=null && !matchesControlledAccount(user,existing.getAccount()))throw new IllegalStateException("固定测试ID已被非测试账号占用");
            if(existing!=null)security.invalidateSessionVersion(user.id());
            mapper.upsertSeedUser(user.id(), user.account(), user.displayName(), encoder.encode(password));
            // 新教学样本仍只是学生，首次教学授予必须真实审核；仅保留旧受控样本已有教学身份，不伪造批准。
            jdbc.update("DELETE FROM user_role WHERE user_id=?",user.id());
            mapper.assignRole(user.id(), "TEACHER".equals(user.role()) ? "STUDENT" : user.role());
            mapper.assignPermission(user.role(), user.permission());
            if("TEACHER".equals(user.role()) && retainedTeacher)mapper.assignRole(user.id(),"TEACHER");
            if(existing!=null) { mapper.bumpSessionVersion(user.id());mapper.revokeAllUserSessions(user.id()); }
        }
        Instant now = Instant.now();
        mapper.upsertSeedIdentity("00000000-0000-4000-8000-000000001101", USERS[0].id(), "EMAIL",
                "student1@example.invalid", now);
        mapper.upsertSeedIdentity("00000000-0000-4000-8000-000000001102", USERS[1].id(), "EMAIL",
                "teacher1@example.invalid", now);
        mapper.upsertSeedIdentity("00000000-0000-4000-8000-000000001104", USERS[2].id(), "EMAIL",
                "teacher2@example.invalid", now);
        mapper.upsertSeedIdentity("00000000-0000-4000-8000-000000001103", USERS[3].id(), "EMAIL",
                "admin1@example.invalid", now);
        seedProtocol("00000000-0000-4000-8000-000000001301", "TERMS", "local-v1",
                "# 开发测试用户协议\n\n本正文仅用于 local/test 自动化与演示，不可用于正式发布。", now);
        seedProtocol("00000000-0000-4000-8000-000000001302", "PRIVACY", "local-v1",
                "# 开发测试隐私政策\n\n本正文仅用于 local/test 自动化与演示，不可用于正式发布。", now);
        mapper.upsertSeedAgreement("00000000-0000-4000-8000-000000001201", USERS[0].id(),
                "TERMS", "local-v1", now);
        mapper.upsertSeedAgreement("00000000-0000-4000-8000-000000001202", USERS[0].id(),
                "PRIVACY", "local-v1", now);
    }

    /** 新增不可变测试正文后将它设为本地当前版本；正文明确禁止用于正式发布。 */
    private void seedProtocol(String id, String type, String version, String body, Instant now) {
        mapper.insertProtocolDocumentIfAbsent(id, type, version, body, now, false, now);
        mapper.clearCurrentProtocolDocument(type);
        mapper.markProtocolDocumentCurrent(id);
    }

    /**
     * 仅受控local/test工具调用：给两个固定、且account匹配的仅教师样本补学生身份，隔离固定管理员的普通能力。
     * 不重置密码/状态、不授予缺失的TEACHER或ADMIN、不新增批准记录、不删除领域数据；缓存失败整体回滚。
     * 返回实际规范化数量，调用者仍须在专用开发库事务内执行，不能用于正式账号迁移。
     */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Normalized normalizeCapabilities() {
        authority.lock();int teachers=0,admins=0;
        for(SeedUser seed:USERS) {
            var user=mapper.findUserByIdForUpdate(seed.id());
            if(user==null)continue;
            if(!matchesControlledAccount(seed,user.getAccount()))throw new IllegalStateException("固定测试ID已被非测试账号占用");
            var roles=mapper.findRoles(seed.id());
            boolean teacher="TEACHER".equals(seed.role()) && roles.equals(java.util.List.of("TEACHER"));
            boolean admin="ADMIN".equals(seed.role()) && roles.contains("ADMIN")
                    && (roles.contains("STUDENT") || roles.contains("TEACHER"));
            if(!teacher && !admin)continue;
            security.invalidateSessionVersion(seed.id());
            if(teacher) { mapper.assignRole(seed.id(),"STUDENT");teachers++; }
            if(admin) {
                jdbc.update("DELETE FROM user_role WHERE user_id=? AND role_id IN(SELECT id FROM `role` WHERE code IN('STUDENT','TEACHER'))",seed.id());admins++;
            }
            if("ACTIVE".equals(user.getStatus()))mapper.bumpSessionVersion(seed.id());
            mapper.revokeAllUserSessions(seed.id());
        }
        return new Normalized(teachers,admins);
    }
    public record Normalized(int upgradedTeacherSamples,int isolatedAdministratorSamples) {}

    /** 固定UUID必须同时匹配当前seed标识或已确认的T04历史测试标识；不接受模糊前缀或普通账号。 */
    private static boolean matchesControlledAccount(SeedUser seed,String account) {
        String historical=switch(seed.account()) {
            case "seed_student_01" -> "student_test_01";
            case "seed_teacher_01" -> "teacher_test_01";
            case "seed_teacher_02" -> "teacher_test_02";
            case "seed_admin_01" -> "admin_test_01";
            default -> throw new IllegalStateException("未知受控样本");
        };
        return seed.account().equals(account) || historical.equals(account);
    }

    private record SeedUser(String id, String account, String displayName, String role, String permission) {}
}
