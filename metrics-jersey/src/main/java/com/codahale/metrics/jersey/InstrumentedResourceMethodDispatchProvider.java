package com.codahale.metrics.jersey;

import com.codahale.metrics.Metric;
import com.sun.jersey.api.core.HttpContext;
import com.sun.jersey.api.model.AbstractResourceMethod;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.server.impl.inject.InjectableValuesProvider;
import com.sun.jersey.server.impl.inject.ServerInjectableProviderFactory;
import com.sun.jersey.spi.container.ResourceMethodDispatchProvider;
import com.sun.jersey.spi.dispatch.RequestDispatcher;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.Timed;

import com.sun.jersey.spi.inject.Injectable;

import java.util.ArrayList;
import java.util.List;

import static com.codahale.metrics.MetricRegistry.name;

class InstrumentedResourceMethodDispatchProvider implements ResourceMethodDispatchProvider {
    private interface MetricProvider<T extends Metric> {
        T getMetric(HttpContext httpContext);
    }

    private static class StaticMetricProvider<T extends Metric> implements MetricProvider<T> {
        private T metric;

        private StaticMetricProvider(T metric) {
            this.metric = metric;
        }

        @Override
        public T getMetric(HttpContext httpContext) {
            return metric;
        }
    }

    private abstract static class ContextSensitiveMetricProvider<T extends Metric> implements MetricProvider<T> {
        private String baseName;
        private InjectableValuesProvider injectableValuesProvider;
        private MetricRegistry metricRegistry;

        private ContextSensitiveMetricProvider(String baseName, InjectableValuesProvider injectableValuesProvider, MetricRegistry metricRegistry) {
            this.baseName = baseName;
            this.injectableValuesProvider = injectableValuesProvider;
            this.metricRegistry = metricRegistry;
        }

        protected abstract T createMetricFromName(MetricRegistry metricRegistry, String name);

        @Override
        public T getMetric(HttpContext httpContext) {
            String formattedName = String.format(baseName, injectableValuesProvider.getInjectableValues(httpContext));
            return createMetricFromName(metricRegistry, formattedName);
        }
    }

    private static class TimedRequestDispatcher implements RequestDispatcher {
        private final RequestDispatcher underlying;
        private final Timer timer;

        private TimedRequestDispatcher(RequestDispatcher underlying, Timer timer) {
            this.underlying = underlying;
            this.timer = timer;
        }

        @Override
        public void dispatch(Object resource, HttpContext httpContext) {
            final Timer.Context context = timer.time();
            try {
                underlying.dispatch(resource, httpContext);
            } finally {
                context.stop();
            }
        }
    }

    private static class MeteredRequestDispatcher implements RequestDispatcher {
        private final RequestDispatcher underlying;
        private final Meter meter;

        private MeteredRequestDispatcher(RequestDispatcher underlying, Meter meter) {
            this.underlying = underlying;
            this.meter = meter;
        }

        @Override
        public void dispatch(Object resource, HttpContext httpContext) {
            meter.mark();
            underlying.dispatch(resource, httpContext);
        }
    }

    private static class ExceptionMeteredRequestDispatcher implements RequestDispatcher {
        private final RequestDispatcher underlying;
        private final Meter meter;
        private final Class<? extends Throwable> exceptionClass;

        private ExceptionMeteredRequestDispatcher(RequestDispatcher underlying,
                                                  Meter meter,
                                                  Class<? extends Throwable> exceptionClass) {
            this.underlying = underlying;
            this.meter = meter;
            this.exceptionClass = exceptionClass;
        }

        @Override
        public void dispatch(Object resource, HttpContext httpContext) {
            try {
                underlying.dispatch(resource, httpContext);
            } catch (Exception e) {
                if (exceptionClass.isAssignableFrom(e.getClass()) ||
                        (e.getCause() != null && exceptionClass.isAssignableFrom(e.getCause().getClass()))) {
                    meter.mark();
                }
                InstrumentedResourceMethodDispatchProvider.<RuntimeException>throwUnchecked(e);
            }
        }
    }

    /*
     * A dirty hack to allow us to throw exceptions of any type without bringing down the unsafe
     * thunder.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Exception> void throwUnchecked(Throwable e) throws T {
        throw (T) e;
    }

    private final ResourceMethodDispatchProvider provider;
    private final MetricRegistry registry;

    public InstrumentedResourceMethodDispatchProvider(ResourceMethodDispatchProvider provider,
                                                      MetricRegistry registry) {
        this.provider = provider;
        this.registry = registry;
    }

    @Override
    public RequestDispatcher create(AbstractResourceMethod method) {
        RequestDispatcher dispatcher = provider.create(method);
        if (dispatcher == null) {
            return null;
        }

        List<Injectable> parameterizedInjectables = new ArrayList<Injectable>(method.getParameters().size());
        ServerInjectableProviderFactory serverInjectableProviderFactory = new ServerInjectableProviderFactory();
        for (int i = 0; i < method.getParameters().size(); i++) {
            parameterizedInjectables.add(serverInjectableProviderFactory.getInjectable(method.getMethod(), method.getParameters().get(i), ComponentScope.PerRequest));
        }
        InjectableValuesProvider injectableValuesProvider = new InjectableValuesProvider(parameterizedInjectables);

        if (method.getMethod().isAnnotationPresent(Timed.class)) {
            final Timed annotation = method.getMethod().getAnnotation(Timed.class);
            final String name = chooseName(annotation.name(), annotation.absolute(), method);
            final Timer timer = registry.timer(name);
            dispatcher = new TimedRequestDispatcher(dispatcher, timer);
        }

        if (method.getMethod().isAnnotationPresent(Metered.class)) {
            final Metered annotation = method.getMethod().getAnnotation(Metered.class);
            final String name = chooseName(annotation.name(), annotation.absolute(), method);
            final Meter meter = registry.meter(name);
            dispatcher = new MeteredRequestDispatcher(dispatcher, meter);
        }

        if (method.getMethod().isAnnotationPresent(ExceptionMetered.class)) {
            final ExceptionMetered annotation = method.getMethod()
                                                      .getAnnotation(ExceptionMetered.class);
            final String name = chooseName(annotation.name(),
                                           annotation.absolute(),
                                           method,
                                           ExceptionMetered.DEFAULT_NAME_SUFFIX);
            final Meter meter = registry.meter(name);
            dispatcher = new ExceptionMeteredRequestDispatcher(dispatcher,
                                                               meter,
                                                               annotation.cause());
        }

        return dispatcher;
    }

    private String chooseName(String explicitName, boolean absolute, AbstractResourceMethod method, String... suffixes) {
        if (explicitName != null && !explicitName.isEmpty()) {
            if (absolute) {
                return explicitName;
            }
            return name(method.getDeclaringResource().getResourceClass(), explicitName);
        }
        return name(name(method.getDeclaringResource().getResourceClass(),
                         method.getMethod().getName()),
                    suffixes);
    }
}
