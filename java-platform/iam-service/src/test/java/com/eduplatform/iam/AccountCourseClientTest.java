package com.eduplatform.iam;

import static org.assertj.core.api.Assertions.*;
import com.eduplatform.iam.service.AccountCourseClient;
import com.eduplatform.iam.service.AuthException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 使用本机HTTP端口验证内部协议失败语义；无真实秘密或数据库，响应缺字段不得冒充零影响/已应用。 */
class AccountCourseClientTest {
    private final String user=UUID.randomUUID().toString(),cycle=UUID.randomUUID().toString();

    @Test void malformedCourseIdentityIsDependencyFailure()throws Exception {
        withServer("{\"courseIds\":[\"invalid\"]}",200,0,client->
                assertThatThrownBy(()->client.impact(user,true,null)).isInstanceOfSatisfying(AuthException.class,
                        failure->assertThat(failure.status().value()).isEqualTo(503)));
    }
    @Test void missingAcknowledgementDoesNotApplyRestore()throws Exception {
        withServer("{\"teacherId\":\""+user+"\",\"suspensionId\":\""+cycle+"\",\"accessRevision\":1}",200,0,client->
                assertThatThrownBy(()->client.apply(user,cycle,1,false,"test")).isInstanceOf(AuthException.class));
    }
    @Test void redirectAndUnauthorizedResponsesFailClosed()throws Exception {
        for(int status:new int[]{302,403})withServer("{}",status,0,client->
                assertThatThrownBy(()->client.impact(user,true,null)).isInstanceOf(AuthException.class));
    }
    @Test void realSocketTimeoutDoesNotReportSuccess()throws Exception {
        withServer("{}",200,500,client->assertThatThrownBy(()->client.apply(user,cycle,1,true,"test"))
                .isInstanceOfSatisfying(AuthException.class,failure->assertThat(failure.status().value()).isEqualTo(503)));
    }

    /** 用受控回复/延迟模拟依赖边界，finally关闭唯一自建端口，不影响运行服务。 */
    private void withServer(String body,int status,long delay,Scenario scenario)throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try {
                if(delay>0)Thread.sleep(delay);
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);
                exchange.getResponseBody().write(bytes);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            finally {exchange.close();}
        });
        server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort();
            scenario.run(new AccountCourseClient(new ObjectMapper(),url,url,"synthetic-internal-test-secret-32bytes",Duration.ofMillis(100)));
        }finally {server.stop(0);}
    }
    @FunctionalInterface private interface Scenario {void run(AccountCourseClient client);}
}
