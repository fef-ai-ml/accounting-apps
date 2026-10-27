package com.example.common;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Entry point of the shared kernel; bounded-context modules {@code @Import} it. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = CommonModule.class)
public class CommonModule {
}
