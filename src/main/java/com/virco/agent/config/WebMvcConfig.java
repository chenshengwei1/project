package com.virco.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AppProperties props;
    public WebMvcConfig(AppProperties props) { this.props = props; }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String dir = Paths.get(props.getUpload().getDir()).toAbsolutePath().toUri().toString();
        registry.addResourceHandler(props.getUpload().getUrlPrefix() + "/**")
                .addResourceLocations(dir.endsWith("/") ? dir : dir + "/");
    }
}