package com.example.ui.common;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.zkoss.xel.VariableResolver;

/**
 * Lets ZK view models look up Spring beans with {@code @WireVariable}, without the zkspring-core library:
 *
 * <pre>
 * &#64;VariableResolver(SpringBeanVariableResolver.class)
 * public class MyViewModel {
 *     &#64;WireVariable private MyService myService;
 * }
 * </pre>
 */
@Component
public class SpringBeanVariableResolver implements VariableResolver, ApplicationContextAware {

	private static volatile ApplicationContext context;

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		context = applicationContext;
	}

	@Override
	public Object resolveVariable(String name) {
		ApplicationContext ctx = context;
		return ctx != null && ctx.containsBean(name) ? ctx.getBean(name) : null;
	}

}
