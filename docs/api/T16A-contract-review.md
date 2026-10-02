# T16A 契约评审（2026-10-02）

本任务自评审后实施；保留准备提交30000d5，不改变T17A页面或通知权限。

- 外部新增 `GET /api/v1/operations/hot-courses`，匿名只读，固定最多6项；200的READY、EMPTY、UNINITIALIZED、UNAVAILABLE可判别，故障仅影响热点。没有公开重建入口。响应禁止HTTP缓存。
- 上海自然日今天及前6日（含边界），窗口到次日零点失效；统计稳定learnerId的集合并集，不相加日人数。真实学习限定已经落库的VIDEO正有效观看增量、ARTICLE明确完成及COURSE非自动正式提交；报名、草稿、AI和打开页不计。
- 有学习者的公开课程先按学习人数降序、ACTIVE报名人数降序、发布时间降序、courseId升序。无学习者只在发布30日内按发布时间补位，label=NEW；零课程EMPTY，不足6项如实返回。
- Learning提供单课聚合只读协议，在本域一致快照内COUNT DISTINCT真实日事实及ACTIVE报名；既有报名事件仅作失效信号，旧/乱序消息不作加减。真实学习日首次资格同业务事务写热点日标记和Outbox，每学生/课/日最多一次事件，不逐心跳发送。历史仅从确有日期的可靠事实回填。
- Course提供100项keyset公开卡片源（枚举所有课程ID，非公开只返回ID/visible=false）、单课源和封面授权。发布/下架新CoursePortalChanged v1同事务Outbox；暂停恢复复用CourseAvailabilityChanged v1。不改写旧事件版本。
- Operation事件只携带失效需求，inbox eventId+dirty courseId同事务。受控Worker每次最多100课；仅通过API刷新可信最新事实，不跨库。后台初始/定期重建keyset分页，数据库协调行只用于派生发布，各轮持锁后读取源，避免旧重建覆盖后完成的新事件。重建分批释放锁，事件与重建共享写路径；失败不清dirty，不将失败当空源。
- Redis保存完整原子JSON榜单（精确字典序排名，避免浮点加权损坏主辅顺序），单key TTL 75～90秒并有有效时点；请求只GET并裁剪到期封面，无任何领域/SQL回源。没有通用缓存框架、hash或新核心锁。窗口过期后台重建，过期结果不显示。
- Course→Media返回实际expiresAt的短期封面授权；Operation数据库/事件只保留mediaId，URL仅存Redis，提前5秒截止，Worker每5秒刷新前六项，故障封面UNAVAILABLE，不使用旧签名。legacy coverUrl不进入热点。热点不授内容/媒体权限，暂停事件异步延迟受缓存TTL约束。validUntil同时受最老源投影刷新时点＋90秒约束，不能靠重新SET给旧源续命。
- Rabbit独立热点队列及DLQ、3次消费尝试、既有Course/Learning Outbox mandatory confirm/return和有限发布重试。历史FAILED不重放，重建可以修复遗失的失效信号。

评审结论：新增协议兼容旧REST/事件；MySQL事实与实时权限不改变，T16.4未读不缓存。后台每轮最多100课，单源HTTP3秒超时，分页/次数与失败可观测；规模扩大需专项容量调参。
