package com.eduplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** 独立H2与真实HTTP验证IAM内部聚合的授权、UUID/数量边界和ACTIVE学生去重，不接开发库。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:t163_impact;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "platform.learning.account-impact.iam-token=synthetic-iam-impact-secret-at-least-32"})
@ActiveProfiles("test")
class AccountImpactIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    final String secret="synthetic-iam-impact-secret-at-least-32";

    /** 同一学生跨两门ACTIVE课只算一次；其他课和CANCELED报名不计，不输出学生名单。 */
    @Test void countsDistinctActiveStudentsOnly()throws Exception {
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString(),learner=UUID.randomUUID().toString();
        enroll(a,learner,"ACTIVE");enroll(b,learner,"ACTIVE");enroll(a,UUID.randomUUID().toString(),"CANCELED");
        enroll(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"ACTIVE");
        var response=request(Map.of("courseIds",List.of(a,b,a)),"iam-service",secret);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("activeStudents").asLong()).isEqualTo(1);
        assertThat(json.readTree(response.body()).size()).isEqualTo(1);
        assertThat(json.readTree(request(Map.of("courseIds",List.of()),"iam-service",secret).body()).path("activeStudents").asLong()).isZero();
    }
    @Test void rejectsMissingWrongAndOtherServiceCredentials()throws Exception {
        for(var pair:List.of(List.of("",""),List.of("iam-service","wrong"),List.of("course-service",secret)))
            assertThat(request(Map.of("courseIds",List.of()),pair.get(0),pair.get(1)).statusCode()).isEqualTo(401);
    }
    @Test void validatesIdentifiersAndMaximumSize()throws Exception {
        assertThat(request(Map.of("courseIds",List.of("invalid")),"iam-service",secret).statusCode()).isEqualTo(422);
        assertThat(request(Map.of("courseIds",Collections.nCopies(1001,UUID.randomUUID().toString())),"iam-service",secret).statusCode()).isEqualTo(422);
    }
    private void enroll(String course,String learner,String status) {
        jdbc.update("INSERT INTO enrollment(id,learner_id,course_id,course_version_id,status,enrolled_at,created_at,updated_at) VALUES(?,?,?,? ,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(),learner,course,UUID.randomUUID().toString(),status);
    }
    private HttpResponse<String> request(Object body,String caller,String token)throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/internal/v1/account-impact/active-students:count"))
                .header("Content-Type","application/json").header("X-Internal-Caller",caller).header("X-Internal-Service-Token",token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
