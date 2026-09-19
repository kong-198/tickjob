package com.kong.tickjob.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * TickJob 调度中心。
 *
 * <p>启动后访问 {@code http://localhost:8080} 可以看到看板，
 * {@code /swagger-ui.html} 是接口文档。</p>
 */
@SpringBootApplication
public class TickJobApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(TickJobApplication.class, args);
        String port = context.getEnvironment().getProperty("server.port", "8080");
        System.out.printf("""

                TickJob 调度中心已启动
                  控制台   http://localhost:%s
                  接口文档 http://localhost:%s/swagger-ui.html
                  健康检查 http://localhost:%s/api/monitor/overview
                %n""", port, port, port);
    }
}
