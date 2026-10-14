package com.hellen.kidtrip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Java 世界的入口 —— 对应前端的 main.tsx。
 *
 * 为什么一个 Spring Boot 应用只需要这一个注解：
 * @SpringBootApplication 是三个注解的打包：
 *   ① @SpringBootConfiguration —— 声明"这是一个配置类"
 *   ② @EnableAutoConfiguration —— 让 Boot 按 classpath 里的依赖自己装配（有 spring-webmvc 就配好 Tomcat + DispatcherServlet）
 *   ③ @ComponentScan —— 从当前包 com.hellen.kidtrip 往下扫描，把所有 @RestController / @Service 收进容器
 *
 * 所以"包的位置"是有语义的：com.hellen.kidtrip 下面的一切才会被扫到，
 * 放到 com.hellen.other 里的类 Boot 看不见 —— 这是新手最常见的"注入不进去"原因。
 *
 * @ConfigurationPropertiesScan 同理：从本类所在包往下找所有 @ConfigurationProperties 类并绑定配置。
 * 没有它，FlyaiProperties 就只是个没人实例化的普通 record，注入时会报 "no bean of type FlyaiProperties"。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KidtripBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(KidtripBackendApplication.class, args);
    }
}
