package com.eduplatform.iam.service;

import com.eduplatform.iam.domain.UserAccount;
import com.eduplatform.iam.persistence.IamMapper;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 管理员实时授权及共同数据库锁；所有创建/封禁/恢复管理员的事务先锁ADMIN角色行，避免最后管理员竞争。 */
@Component
public class AdminAuthority {
    private final IamMapper mapper;
    private final JdbcTemplate jdbc;
    public AdminAuthority(IamMapper mapper, JdbcTemplate jdbc) { this.mapper = mapper; this.jdbc = jdbc; }

    /** 输入操作者ID，核对当前ACTIVE、独立ADMIN及管理能力；过期JWT中的角色不能替代本域事实。 */
    public void require(String actor) {
        UserAccount user = mapper.findUserById(actor);
        if (user == null || !"ACTIVE".equals(user.getStatus()) || !mapper.findRoles(actor).equals(List.of("ADMIN"))
                || !mapper.findPermissions(actor).contains("administration:access"))
            throw new AuthException(HttpStatus.FORBIDDEN, "IAM.FORBIDDEN", "需要当前有效的独立管理员身份");
    }

    /** 必须在本域READ_COMMITTED写事务最先调用；角色唯一行锁串行化管理员事实变更，失败整体回滚。 */
    public void lock() {
        jdbc.queryForObject("SELECT id FROM `role` WHERE code='ADMIN' FOR UPDATE", Long.class);
    }

    /** 返回当前可用独立管理员人数；调用方必须已持ADMIN角色锁，不能先查数量再锁。 */
    public long activeCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM `user` u JOIN user_role ur ON ur.user_id=u.id JOIN `role` r ON r.id=ur.role_id WHERE r.code='ADMIN' AND u.status='ACTIVE' AND NOT EXISTS (SELECT 1 FROM user_role x JOIN `role` y ON y.id=x.role_id WHERE x.user_id=u.id AND y.code<>'ADMIN')", Long.class);
    }
}
