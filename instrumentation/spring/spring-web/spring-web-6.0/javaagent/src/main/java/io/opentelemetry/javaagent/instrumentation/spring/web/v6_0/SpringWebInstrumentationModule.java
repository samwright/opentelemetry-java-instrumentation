/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.spring.web.v6_0;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static java.util.Collections.singletonList;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.HelperResourceBuilder;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.internal.ExperimentalInstrumentationModule;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class SpringWebInstrumentationModule extends InstrumentationModule
    implements ExperimentalInstrumentationModule {

  public SpringWebInstrumentationModule() {
    super("spring-web", "spring-web-6.0");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // class added in 6.0
    return hasClassesNamed("org.springframework.web.ErrorResponse");
  }

  @Override
  public void registerHelperResources(HelperResourceBuilder helperResourceBuilder) {
    if (!isIndyModule()) {
      // make the filter class file loadable by ClassPathResource - in some cases (e.g.
      // spring-guice,
      // see https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/7428)
      // Spring
      // might want to read the class file metadata; this line will make the filter class file
      // visible
      // to the bean class loader
      helperResourceBuilder.register(
          "org/springframework/web/servlet/v6_0/OpenTelemetryHandlerMappingFilter.class");
    }
  }

  @Override
  public String getModuleGroup() {
    // depends on OpenTelemetryHandlerMappingFilter
    return "servlet";
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return singletonList(new WebApplicationContextInstrumentation());
  }
}
