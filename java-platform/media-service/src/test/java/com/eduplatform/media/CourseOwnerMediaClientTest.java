package com.eduplatform.media;

import static org.assertj.core.api.Assertions.*;
import com.eduplatform.media.service.CourseOwnerMediaClient;
import com.eduplatform.media.service.MediaException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 真 HTTP 验证 Course 的 204/暂停/依赖故障适配；不创建资产或调用 OSS。 */
class CourseOwnerMediaClientTest {
    HttpServer server;
    CourseOwnerMediaClient client;
    volatile int status=204;
    volatile String body="";
    volatile String caller;

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            caller=exchange.getRequestHeaders().getFirst("X-Internal-Caller");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,status==204?-1:bytes.length);
            if(status!=204)exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client=new CourseOwnerMediaClient("http://127.0.0.1:"+server.getAddress().getPort(),Duration.ofSeconds(1),"test-only-media-read-secret-32-bytes");
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void onlyExplicit204AllowsSigningAndCallerIsMedia() {
        client.requireAccess("00000000-0000-4000-8000-000000016161","t161-media-client");
        assertThat(caller).isEqualTo("media-service");
        status=200; body="{}";
        assertThatThrownBy(()->client.requireAccess("00000000-0000-4000-8000-000000016161","t161-invalid-active"))
                .isInstanceOfSatisfying(MediaException.class,e->assertThat(e.status().value()).isEqualTo(503));
    }
    @Test void pausedOwnerAndDependencyFailuresFailClosed() {
        status=409; body="{\"code\":\"COURSE.SUSPENDED\",\"supportEmail\":\"support@example.invalid\"}";
        assertThatThrownBy(()->client.requireAccess("00000000-0000-4000-8000-000000016161","t161-media-paused"))
                .isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo("MEDIA.COURSE_SUSPENDED"));
        status=500; body="{}";
        assertThatThrownBy(()->client.requireAccess("00000000-0000-4000-8000-000000016161","t161-media-unavailable"))
                .isInstanceOfSatisfying(MediaException.class,e->assertThat(e.status().value()).isEqualTo(503));
    }
}
