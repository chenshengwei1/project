package com.virco.agent.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {
    /** 给模型看的参数说明，必填——模型选参数全靠它 */
    String description();

    /** 是否必填，默认 true */
    boolean required() default true;

    /** 覆盖参数名（极少用，比如需要特殊字符时）。留空则取反射参数名。 */
    String name() default "";
}