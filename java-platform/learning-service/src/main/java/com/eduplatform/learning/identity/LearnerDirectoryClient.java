package com.eduplatform.learning.identity;

import java.util.List;
import java.util.Map;

/**
 * IAM 昵称批量读取端口。Learning 只接收短期展示投影，不把昵称复制为本域事实。
 */
public interface LearnerDirectoryClient {
    /**
     * @param learnerIds 当前成绩册中需要展示的学习者稳定 ID，数量由调用方限制在成绩册页大小内
     * @return 昵称映射及部分依赖失败标记；IAM 不可用时映射可以为空但不能丢失 Learning 成绩事实
     */
    NicknameLookup nicknames(List<String> learnerIds, String traceId);

    record NicknameLookup(Map<String, String> values, boolean dependencyFailed) {
        public NicknameLookup {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }
}
