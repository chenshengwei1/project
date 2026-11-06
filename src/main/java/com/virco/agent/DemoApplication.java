package com.virco.agent;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import lombok.extern.slf4j.Slf4j;

@Slf4j 
@SpringBootApplication
@ConfigurationPropertiesScan("com.virco.agent.config") // 指定你的配置类所在包
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    // ③ 上下文创建后：打印容器中所有 Bean
    //    ApplicationRunner 在所有单例 Bean 初始化完成后执行，此时上下文完全就绪
    @Bean
    public ApplicationRunner beanPrinter(ApplicationContext ctx) {
        return args -> {
            String[] names = ctx.getBeanDefinitionNames();
            log.info("【上下文创建后】开始打印 Bean，共 {} 个", names.length);
            for (String name : names) {
                Class<?> type = ctx.getType(name);   // 用 getType 不触发实例化，安全
                log.info("  {} -> {}", name, type != null ? type.getName() : "unknown");
            }
        };
    }
    
}