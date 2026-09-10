"""严格映射 T13.1 冻结 HTTP/SSE、意图、角色和工具枚举。"""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, model_validator


class StrictModel(BaseModel):
    """所有内部协议对象拒绝未知字段，避免浏览器字段被意外提升为可信事实。"""

    model_config = ConfigDict(extra="forbid")


class PrimaryIntent(str, Enum):
    MATH_TUTORING = "MATH_TUTORING"
    PRACTICE_COACHING = "PRACTICE_COACHING"
    STUDY_PLANNING = "STUDY_PLANNING"
    PLATFORM_HELP = "PLATFORM_HELP"
    CONVERSATION = "CONVERSATION"
    UNSUPPORTED = "UNSUPPORTED"
    ACTION_CONTROL = "ACTION_CONTROL"


class SecondaryIntent(str, Enum):
    SOLVE_PROBLEM = "SOLVE_PROBLEM"
    EXPLAIN_CONCEPT = "EXPLAIN_CONCEPT"
    COURSE_QUESTION = "COURSE_QUESTION"
    CHECK_STUDENT_WORK = "CHECK_STUDENT_WORK"
    GIVE_HINT = "GIVE_HINT"
    REVIEW_WRONG_QUESTION = "REVIEW_WRONG_QUESTION"
    RECOMMEND_PRACTICE = "RECOMMEND_PRACTICE"
    CREATE_PLAN = "CREATE_PLAN"
    ADJUST_PLAN = "ADJUST_PLAN"
    LEARNING_ADVICE = "LEARNING_ADVICE"
    SMALL_TALK = "SMALL_TALK"
    UNSUPPORTED = "UNSUPPORTED"
    ACTION_CONFIRMATION = "ACTION_CONFIRMATION"
    ACTION_CANCELLATION = "ACTION_CANCELLATION"


class AgentRole(str, Enum):
    COORDINATOR = "COORDINATOR"
    MATH_TUTOR = "MATH_TUTOR"
    PRACTICE_COACH = "PRACTICE_COACH"
    STUDY_PLANNER = "STUDY_PLANNER"
    PLATFORM_HELP = "PLATFORM_HELP"


class ToolName(str, Enum):
    QUESTION_SEARCH = "QUESTION_SEARCH"
    KNOWLEDGE_SEARCH = "KNOWLEDGE_SEARCH"
    PREREQUISITE_GRAPH = "PREREQUISITE_GRAPH"
    STUDENT_WRONG_QUESTIONS = "STUDENT_WRONG_QUESTIONS"
    STUDENT_SUBMISSIONS = "STUDENT_SUBMISSIONS"
    STUDENT_PUBLISHED_GRADES = "STUDENT_PUBLISHED_GRADES"
    STUDENT_PROGRESS = "STUDENT_PROGRESS"
    COURSE_TEXT_SEARCH = "COURSE_TEXT_SEARCH"
    AUTHORIZED_IMAGE_UNDERSTANDING = "AUTHORIZED_IMAGE_UNDERSTANDING"
    USER_HELP_SEARCH = "USER_HELP_SEARCH"


class TerminalStatus(str, Enum):
    COMPLETED = "COMPLETED"
    DEGRADED = "DEGRADED"
    LIMIT_REACHED = "LIMIT_REACHED"
    CANCELLED = "CANCELLED"
    FAILED = "FAILED"


class StudentAttachedWork(StrictModel):
    attachedByStudent: Literal[True]
    content: str = Field(min_length=1, max_length=20_000)
    status: Literal["STUDENT_DRAFT"]


class UserMessage(StrictModel):
    text: str = Field(min_length=1, max_length=10_000)
    locale: Literal["zh-CN"]
    studentAttachedWork: StudentAttachedWork | None = None


class RecentMessage(StrictModel):
    """由 Java 持久化事实构造的最近消息；不承载上下文 JSON、工具结果或临时授权。"""

    messageId: UUID
    role: Literal["USER", "ASSISTANT"]
    content: str = Field(min_length=1, max_length=10_000)
    completeness: Literal["COMPLETE", "PARTIAL"]
    sequenceNo: int = Field(ge=1)


class LearningPreference(StrictModel):
    kind: Literal["EXPLANATION_STYLE", "DETAIL_LEVEL", "HINT_FIRST"]
    value: str = Field(min_length=1, max_length=80)
    source: Literal["EXPLICIT", "INFERRED"]


class SafeQuestionChild(StrictModel):
    questionVersionId: UUID
    questionType: Literal[
        "SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER"
    ]
    stem: str = Field(min_length=1, max_length=20_000)
    options: list[str] = Field(default_factory=list, max_length=20)


class SafeQuestionProjection(StrictModel):
    questionType: Literal[
        "SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER", "COMPOSITE"
    ]
    stem: str = Field(min_length=1, max_length=30_000)
    options: list[str] = Field(default_factory=list, max_length=20)
    children: list[SafeQuestionChild] = Field(default_factory=list, max_length=20)


class AuthorizedQuestionContext(StrictModel):
    questionId: UUID
    questionVersionId: UUID
    scope: Literal["ACTIVE_SYSTEM", "AUTHORIZED_ASSESSMENT_SNAPSHOT"]
    safeProjection: SafeQuestionProjection


class AuthorizedCourseContext(StrictModel):
    courseId: UUID
    courseVersionId: str = Field(min_length=1, max_length=64)
    lessonId: UUID | None = None
    accessDecision: Literal["AUTHORIZED_BY_JAVA"]
    textResourceRefs: list[UUID] = Field(default_factory=list, max_length=32)


class AuthorizedLearningFactRef(StrictModel):
    factType: Literal["WRONG_QUESTION", "STUDENT_SUBMISSION", "PUBLISHED_GRADE", "LEARNING_PROGRESS"]
    factId: UUID
    ownerScope: Literal["USER_SELF"]
    revision: int = Field(ge=0)


class TemporaryMediaAccess(StrictModel):
    url: HttpUrl
    expiresAt: datetime
    confidentiality: Literal["SHORT_LIVED_SECRET"]


class AuthorizedMediaReference(StrictModel):
    mediaId: UUID
    purpose: Literal["QUESTION_IMAGE", "AI_QUESTION_IMAGE"]
    mimeType: Literal["image/jpeg", "image/png", "image/webp"]
    sizeBytes: int = Field(ge=1, le=10_485_760)
    accessDecision: Literal["AUTHORIZED_BY_JAVA"]
    temporaryAccess: TemporaryMediaAccess | None = None


class AuthorizedMemorySummary(StrictModel):
    summaryId: UUID
    revision: int = Field(ge=1)
    text: str = Field(min_length=1, max_length=4_000)
    authorizedByJava: Literal[True]


class ResourceScope(StrictModel):
    scopeType: Literal["PUBLIC_JUNIOR_MATH", "USER_SELF", "QUESTION_VERSION", "COURSE", "MEDIA", "HELP_KB"]
    resourceIds: list[str] | None = Field(default=None, min_length=1, max_length=100)


class ToolGrant(StrictModel):
    toolName: ToolName
    resourceScopes: list[ResourceScope] = Field(min_length=1, max_length=16)


class TrustedContext(StrictModel):
    userId: UUID
    educationStage: Literal["JUNIOR"]
    grade: Literal["GRADE_7", "GRADE_8", "GRADE_9"] | None = None
    entryPoint: Literal["INDEPENDENT_CHAT", "PRACTICE_FLOATING"]
    authorizedAt: datetime
    preferences: list[LearningPreference] = Field(default_factory=list, max_length=8)
    question: AuthorizedQuestionContext | None = None
    course: AuthorizedCourseContext | None = None
    learningFacts: list[AuthorizedLearningFactRef] = Field(default_factory=list, max_length=32)
    media: list[AuthorizedMediaReference] = Field(default_factory=list, max_length=8)
    memorySummaries: list[AuthorizedMemorySummary] = Field(default_factory=list, max_length=8)
    toolGrants: list[ToolGrant] = Field(min_length=1, max_length=16)


class RunBudget(StrictModel):
    maxOrchestrationSteps: int = Field(ge=1, le=32)
    maxLlmCalls: int = Field(ge=1, le=16)
    reservedFinalLlmCalls: int = Field(ge=0, le=2)
    maxToolAttempts: int = Field(ge=0, le=20)
    maxExpertTransfers: int = Field(ge=0, le=4)
    maxSameToolArgumentsAttempts: int = Field(ge=1, le=3)
    maxTeachingRemediations: int = Field(ge=0, le=3)
    teachingRemediationOrder: list[
        Literal["REPHRASE", "SIMPLE_EXAMPLE", "PREREQUISITE_FALLBACK"]
    ] = Field(min_length=3, max_length=3)
    totalTimeMs: int = Field(ge=1_000, le=180_000)

    @model_validator(mode="after")
    def validate_budget(self) -> "RunBudget":
        if len(set(self.teachingRemediationOrder)) != 3:
            raise ValueError("teachingRemediationOrder 必须包含三个不同策略")
        if self.reservedFinalLlmCalls > self.maxLlmCalls:
            raise ValueError("reservedFinalLlmCalls 不能超过 maxLlmCalls")
        return self


class TimeoutPolicy(StrictModel):
    firstEventTimeoutMs: int = Field(ge=1_000, le=30_000)
    heartbeatIntervalMs: int = Field(ge=1_000, le=30_000)
    idleTimeoutMs: int = Field(ge=5_000, le=60_000)
    slowClientBufferMaxEvents: int = Field(ge=8, le=256)


class AgentRunRequest(StrictModel):
    protocolVersion: Literal[1]
    conversationId: UUID
    messageId: UUID
    runId: UUID
    clientRequestId: UUID
    traceId: str = Field(pattern=r"^[A-Za-z0-9_-]{8,64}$")
    requestedAt: datetime
    userMessage: UserMessage
    recentMessages: list[RecentMessage] = Field(default_factory=list, max_length=12)
    trustedContext: TrustedContext
    budget: RunBudget
    timeoutPolicy: TimeoutPolicy

    @model_validator(mode="after")
    def validate_recent_messages(self) -> "AgentRunRequest":
        if sum(len(item.content) for item in self.recentMessages) > 16_000:
            raise ValueError("recentMessages 总字符数不能超过 16000")
        sequences = [item.sequenceNo for item in self.recentMessages]
        if sequences != sorted(set(sequences)):
            raise ValueError("recentMessages 必须按唯一 sequenceNo 升序")
        if any(item.messageId == self.messageId for item in self.recentMessages):
            raise ValueError("recentMessages 不得重复当前用户消息")
        return self


class CancelRunRequest(StrictModel):
    cancelRequestId: UUID
    traceId: str = Field(pattern=r"^[A-Za-z0-9_-]{8,64}$")
    requestedAt: datetime
    reason: Literal["USER_STOPPED", "CLIENT_DISCONNECTED", "PAGE_CLOSED", "SWITCH_TO_FULL_PAGE"]


class IntentSlots(StrictModel):
    problemText: str | None = Field(default=None, min_length=1, max_length=10_000)
    conceptName: str | None = Field(default=None, min_length=1, max_length=200)
    questionId: UUID | None = None
    courseId: UUID | None = None
    lessonId: UUID | None = None
    studentWork: str | None = Field(default=None, min_length=1, max_length=20_000)
    hintLevel: Literal["LIGHT", "MEDIUM", "STRONG"] | None = None
    wrongQuestionId: UUID | None = None
    practiceTopic: str | None = Field(default=None, min_length=1, max_length=200)
    planGoal: str | None = Field(default=None, min_length=1, max_length=500)
    planConstraints: str | None = Field(default=None, min_length=1, max_length=1_000)
    platformTopic: str | None = Field(default=None, min_length=1, max_length=200)
    actionId: UUID | None = None


class RoutedSubRequest(StrictModel):
    sequence: int = Field(ge=1, le=2)
    secondaryIntent: SecondaryIntent
    role: AgentRole
    slots: IntentSlots


class IntentDecision(StrictModel):
    primaryIntent: PrimaryIntent
    secondaryIntent: SecondaryIntent
    role: AgentRole
    resolution: Literal["ROUTED", "NEEDS_CLARIFICATION"]
    slots: IntentSlots
    missingSlots: list[str] = Field(default_factory=list, max_length=8)
    subRequests: list[RoutedSubRequest] = Field(default_factory=list, max_length=2)


class SafeError(StrictModel):
    code: str = Field(pattern=r"^AGENT\.[A-Z0-9_]{3,48}$")
    message: str = Field(min_length=1, max_length=300)
    retryable: bool
    traceId: str


class SafeToolEvidence(StrictModel):
    evidenceId: str = Field(min_length=1, max_length=128)
    evidenceType: Literal[
        "QUESTION", "KNOWLEDGE_POINT", "PREREQUISITE_RELATION", "STUDENT_FACT",
        "COURSE_TEXT", "IMAGE_UNDERSTANDING", "USER_HELP"
    ]
    title: str = Field(min_length=1, max_length=200)
    summary: str = Field(min_length=1, max_length=4_000)
    accessDecision: Literal["AUTHORIZED_BY_JAVA"] = "AUTHORIZED_BY_JAVA"
    revision: int | None = Field(default=None, ge=0)


class ToolResult(StrictModel):
    toolCallId: UUID
    toolName: ToolName
    status: Literal["SUCCEEDED", "EMPTY", "REJECTED", "TIMEOUT", "FAILED"]
    traceId: str
    elapsedMs: int = Field(ge=0, le=30_000)
    evidence: list[SafeToolEvidence] = Field(default_factory=list, max_length=50)
    error: SafeError | None = None


def event_dict(event_type: str, run_id: UUID, sequence: int, trace_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    """构造单个冻结 SSE 事件；调用方保证 sequence 连续且唯一终态最后发送。"""

    return {
        "eventType": event_type,
        "runId": str(run_id),
        "sequence": sequence,
        "traceId": trace_id,
        "emittedAt": datetime.now().astimezone().isoformat(),
        "payload": payload,
    }
