package com.example.ui.common;

import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.zkoss.zk.au.http.DHtmlUpdateServlet;
import org.zkoss.zk.ui.http.DHtmlLayoutServlet;
import org.zkoss.zk.ui.http.HttpSessionListener;

/**
 * Plain servlet-level ZK setup, equivalent to the ZK entries of a web.xml (no zkspringboot starter).
 * Every deployable UI app imports this once.
 *
 * <p>ZUL pages are packaged on the classpath under {@code web/} and addressed as {@code ~./...}, so each UI module
 * can ship its pages inside its own jar.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = ZkSupport.class)
public class ZkSupport {

	static final String UPDATE_URI = "/zkau";

	/** Renders *.zul pages. Must start first so the ZK WebManager exists before the update servlet. */
	@Bean
	ServletRegistrationBean<DHtmlLayoutServlet> zkLayoutServlet() {
		ServletRegistrationBean<DHtmlLayoutServlet> bean =
				new ServletRegistrationBean<>(new DHtmlLayoutServlet(), "*.zul", "*.zhtml");
		bean.addInitParameter("update-uri", UPDATE_URI);
		bean.setLoadOnStartup(1);
		return bean;
	}

	/** Handles ZK's AJAX updates and static ZK resources (JS, CSS, images, classpath {@code web/} files). */
	@Bean
	ServletRegistrationBean<DHtmlUpdateServlet> zkUpdateServlet() {
		ServletRegistrationBean<DHtmlUpdateServlet> bean =
				new ServletRegistrationBean<>(new DHtmlUpdateServlet(), UPDATE_URI + "/*");
		bean.setLoadOnStartup(2);
		return bean;
	}

	/** Cleans up ZK desktops when the HTTP session ends. */
	@Bean
	ServletListenerRegistrationBean<HttpSessionListener> zkSessionListener() {
		return new ServletListenerRegistrationBean<>(new HttpSessionListener());
	}

}
