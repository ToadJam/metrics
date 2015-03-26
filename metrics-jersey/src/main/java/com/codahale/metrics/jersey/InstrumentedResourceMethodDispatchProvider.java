package com.codahale.metrics.jersey;

import com.codahale.metrics.Metric;
import com.codahale.metrics.annotation.MetricNameParam;
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
import com.sun.jersey.api.model.Parameter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.codahale.metrics.MetricRegistry.name;

class InstrumentedResourceMethodDispatchProvider implements ResourceMethodDispatchProvider {
    private interface MetricBuilder<T extends Metric> {
        T buildMetric(MetricRegistry metricRegistry, String name);
    }

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

    private static class ContextSensitiveMetricProvider<T extends Metric> implements MetricProvider<T> {
        private String baseName;
        private InjectableValuesProvider injectableValuesProvider;
        private MetricRegistry metricRegistry;
        private MetricBuilder<T> metricBuilder;

        private ContextSensitiveMetricProvider(String baseName, InjectableValuesProvider injectableValuesProvider, MetricRegistry metricRegistry, MetricBuilder<T> metricBuilder) {
            this.baseName = baseName;
            this.injectableValuesProvider = injectableValuesProvider;
            this.metricRegistry = metricRegistry;
            this.metricBuilder = metricBuilder;
        }

        @Override
        public T getMetric(HttpContext httpContext) {
            String formattedName = String.format(baseName, injectableValuesProvider.getInjectableValues(httpContext));
            return metricBuilder.buildMetric(metricRegistry, formattedName);
        }
    }

    private static class TimedRequestDispatcher implements RequestDispatcher {
        private final RequestDispatcher underlying;
        private final MetricProvider<Timer> metricProvider;

        private TimedRequestDispatcher(RequestDispatcher underlying, MetricProvider<Timer> metricProvider) {
            this.underlying = underlying;
            this.metricProvider = metricProvider;
        }

        @Override
        public void dispatch(Object resource, HttpContext httpContext) {
            Timer timer = metricProvider.getMetric(httpContext);
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
        private final MetricProvider<Meter> metricProvider;

        private MeteredRequestDispatcher(RequestDispatcher underlying, MetricProvider<Meter> metricProvider) {
            this.underlying = underlying;
            this.metricProvider = metricProvider;
        }

        @Override
        public void dispatch(Object resource, HttpContext httpContext) {
            Meter meter = metricProvider.getMetric(httpContext);
            meter.mark();
            underlying.dispatch(resource, httpContext);
        }
    }

    private static class ExceptionMeteredRequestDispatcher implements RequestDispatcher {
        private final RequestDispatcher underlying;
        private final MetricProvider<Meter> metricProvider;
        private final Class<? extends Throwable> exceptionClass;

        private ExceptionMeteredRequestDispatcher(RequestDispatcher underlying,
                                                  MetricProvider<Meter> metricProvider,
                                                  Class<? extends Throwable> exceptionClass) {
            this.underlying = underlying;
            this.metricProvider = metricProvider;
            this.exceptionClass = exceptionClass;
        }

        @Override
        public void dispatch(Object resource, HttpContext httpContext) {
            try {
                underlying.dispatch(resource, httpContext);
            } catch (Exception e) {
                if (exceptionClass.isAssignableFrom(e.getClass()) ||
                        (e.getCause() != null && exceptionClass.isAssignableFrom(e.getCause().getClass()))) {
                    Meter meter = metricProvider.getMetric(httpContext);
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

        InjectableValuesProvider injectableValuesProvider = getInjectableValuesProvider(method);
        if (method.getMethod().isAnnotationPresent(Timed.class)) {
            final Timed annotation = method.getMethod().getAnnotation(Timed.class);
            final String name = chooseName(annotation.name(), annotation.absolute(), method);
            MetricProvider<Timer> metricProvider = getMetricProvider(name, injectableValuesProvider, new MetricBuilder<Timer>() {
                @Override
                public Timer buildMetric(MetricRegistry metricRegistry, String name) {
                    return metricRegistry.timer(name);
                }
            });
            dispatcher = new TimedRequestDispatcher(dispatcher, metricProvider);
        }

        if (method.getMethod().isAnnotationPresent(Metered.class)) {
            final Metered annotation = method.getMethod().getAnnotation(Metered.class);
            final String name = chooseName(annotation.name(), annotation.absolute(), method);
            MetricProvider<Meter> metricProvider = getMetricProvider(name, injectableValuesProvider, new MetricBuilder<Meter>() {
                @Override
                public Meter buildMetric(MetricRegistry metricRegistry, String name) {
                    return metricRegistry.meter(name);
                }
            });
            dispatcher = new MeteredRequestDispatcher(dispatcher, metricProvider);
        }

        if (method.getMethod().isAnnotationPresent(ExceptionMetered.class)) {
            final ExceptionMetered annotation = method.getMethod()
                                                      .getAnnotation(ExceptionMetered.class);
            final String name = chooseName(annotation.name(),
                                           annotation.absolute(),
                                           method,
                                           ExceptionMetered.DEFAULT_NAME_SUFFIX);
            MetricProvider<Meter> metricProvider = getMetricProvider(name, injectableValuesProvider, new MetricBuilder<Meter>() {
                @Override
                public Meter buildMetric(MetricRegistry metricRegistry, String name) {
                    return metricRegistry.meter(name);
                }
            });
            dispatcher = new ExceptionMeteredRequestDispatcher(dispatcher,
                                                               metricProvider,
                                                               annotation.cause());
        }

        return dispatcher;
    }

    private InjectableValuesProvider getInjectableValuesProvider(AbstractResourceMethod method) {
        ServerInjectableProviderFactory serverInjectableProviderFactory = new ServerInjectableProviderFactory();

        InjectableValuesProvider injectableValuesProvider = null;
        Map<Integer, Injectable> injectableMap = new HashMap<Integer, Injectable>();
        for (Parameter parameter : method.getParameters()) {
            MetricNameParam metricNameParam = parameter.getAnnotation(MetricNameParam.class);
            if (metricNameParam != null) {
                injectableMap.put(metricNameParam.value(), serverInjectableProviderFactory.getInjectable(method.getMethod(), parameter, ComponentScope.PerRequest));
            }
        }
        if (injectableMap.size() > 0) {
            List<Injectable> parameterizedInjectables = new ArrayList<Injectable>(injectableMap.size());
            for (int i = 0; i < injectableMap.size(); i++) {
                Injectable injectableForIndex = injectableMap.get(i);
                if (injectableForIndex == null) {
                    throw new IllegalArgumentException("Provided MetricNameParam values were non-contiguous");
                }
                parameterizedInjectables.add(injectableForIndex);
            }
            injectableValuesProvider = new InjectableValuesProvider(parameterizedInjectables);
        }

        return injectableValuesProvider;
    }

    private <T extends Metric> MetricProvider<T> getMetricProvider(String name, InjectableValuesProvider injectableValuesProvider, MetricBuilder<T> metricBuilder) {
        if (injectableValuesProvider != null) {
            return new ContextSensitiveMetricProvider<T>(name, injectableValuesProvider, registry, metricBuilder);
        } else {
            return new StaticMetricProvider<T>(metricBuilder.buildMetric(registry, name));
        }
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
