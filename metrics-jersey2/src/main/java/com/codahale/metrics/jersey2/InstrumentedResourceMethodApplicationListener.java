package com.codahale.metrics.jersey2;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.MetricNameParam;
import com.codahale.metrics.annotation.Timed;
import jersey.repackaged.com.google.common.collect.ImmutableMap;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.jersey.server.model.Invocable;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.server.model.ResourceMethod;
import org.glassfish.jersey.server.monitoring.ApplicationEvent;
import org.glassfish.jersey.server.monitoring.ApplicationEventListener;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.glassfish.jersey.server.monitoring.RequestEventListener;

import javax.ws.rs.ext.Provider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * An application event listener that listens for Jersey application initialization to
 * be finished, then creates a map of resource method that have metrics annotations.
 * <p/>
 * Finally, it listens for method start events, and returns a {@link RequestEventListener}
 * that updates the relevant metric for suitably annotated methods when it gets the
 * request events indicating that the method is about to be invoked, or just got done
 * being invoked.
 */

@Provider
public class InstrumentedResourceMethodApplicationListener implements ApplicationEventListener {

    private final MetricRegistry metrics;
    private final ServiceLocator serviceLocator;
    private ImmutableMap<Method, MetricProvider<Timer>> timers = ImmutableMap.of();
    private ImmutableMap<Method, MetricProvider<Meter>> meters = ImmutableMap.of();
    private ImmutableMap<Method, ExceptionMeterMetric> exceptionMeters = ImmutableMap.of();

    /**
     * Construct an application event listener using the given metrics registry.
     * <p/>
     * <p/>
     * When using this constructor, the {@link InstrumentedResourceMethodApplicationListener}
     * should be added to a Jersey {@code ResourceConfig} as a singleton.
     *
     * @param metrics a {@link MetricRegistry}
     */
    public InstrumentedResourceMethodApplicationListener(final MetricRegistry metrics, final ServiceLocator serviceLocator) {
        this.metrics = metrics;
        this.serviceLocator = serviceLocator;
    }

    private interface MetricBuilder<T extends Metric> {
        T buildMetric(MetricRegistry metricRegistry, String name);
    }

    private interface MetricProvider<T extends Metric> {
        T getMetric(Invocable invocable);
    }

    private static class StaticMetricProvider<T extends Metric> implements MetricProvider<T> {
        private T metric;

        private StaticMetricProvider(T metric) {
            this.metric = metric;
        }

        @Override
        public T getMetric(Invocable invocable) {
            return metric;
        }
    }

    private static class ContextSensitiveMetricProvider<T extends Metric> implements MetricProvider<T> {
        private final String baseName;
        private final MetricRegistry metricRegistry;
        private final MetricBuilder<T> metricBuilder;
        private final List<Integer> paramIndexList;
        private final ServiceLocator serviceLocator;

        private ContextSensitiveMetricProvider(String baseName, MetricRegistry metricRegistry, MetricBuilder<T> metricBuilder, List<Integer> paramIndexList, ServiceLocator serviceLocator) {
            this.baseName = baseName;
            this.metricRegistry = metricRegistry;
            this.metricBuilder = metricBuilder;
            this.paramIndexList = paramIndexList;
            this.serviceLocator = serviceLocator;
        }

        @Override
        public T getMetric(Invocable invocable) {
            java.util.List<org.glassfish.hk2.api.Factory<?>> valueProviders = invocable.getValueProviders(serviceLocator);
            Object[] paramList = new Object[paramIndexList.size()];
            for (int i = 0; i < paramIndexList.size(); i++) {
                paramList[i] = valueProviders.get(i).provide();
            }
            String formattedName = String.format(baseName, paramList);
            return metricBuilder.buildMetric(metricRegistry, formattedName);
        }
    }

    /**
     * A private class to maintain the metric for a method annotated with the
     * {@link ExceptionMetered} annotation, which needs to maintain both a meter
     * and a cause for which the meter should be updated.
     */
    private class ExceptionMeterMetric {
        public final MetricProvider<Meter> meterProvider;
        public final Class<? extends Throwable> cause;

        public ExceptionMeterMetric(final MetricRegistry registry,
                                    final ResourceMethod method,
                                    final ExceptionMetered exceptionMetered) {
            final String name = chooseName(exceptionMetered.name(),
                    exceptionMetered.absolute(), method, ExceptionMetered.DEFAULT_NAME_SUFFIX);
            this.meterProvider = meterMetric(name, registry, method);
            this.cause = exceptionMetered.cause();
        }
    }

    private static class TimerRequestEventListener implements RequestEventListener {
        private final ImmutableMap<Method, MetricProvider<Timer>> timers;
        private Timer.Context context = null;

        public TimerRequestEventListener(final ImmutableMap<Method, MetricProvider<Timer>> timers) {
            this.timers = timers;
        }

        @Override
        public void onEvent(RequestEvent event) {
            if (event.getType() == RequestEvent.Type.RESOURCE_METHOD_START) {
                Invocable invocable = event.getUriInfo().getMatchedResourceMethod().getInvocable();
                final MetricProvider<Timer> timer = this.timers.get(invocable.getDefinitionMethod());
                if (timer != null) {
                    this.context = timer.getMetric(invocable).time();
                }
            } else if (event.getType() == RequestEvent.Type.RESOURCE_METHOD_FINISHED) {
                if (this.context != null) {
                    this.context.close();
                }
            }
        }
    }

    private static class MeterRequestEventListener implements RequestEventListener {
        private final ImmutableMap<Method, MetricProvider<Meter>> meters;

        public MeterRequestEventListener(final ImmutableMap<Method, MetricProvider<Meter>> meters) {
            this.meters = meters;
        }

        @Override
        public void onEvent(RequestEvent event) {
            if (event.getType() == RequestEvent.Type.RESOURCE_METHOD_START) {
                Invocable invocable = event.getUriInfo().getMatchedResourceMethod().getInvocable();
                final MetricProvider<Meter> meter = this.meters.get(invocable.getDefinitionMethod());
                if (meter != null) {
                    meter.getMetric(invocable).mark();
                }
            }
        }
    }

    private static class ExceptionMeterRequestEventListener implements RequestEventListener {
        private final ImmutableMap<Method, ExceptionMeterMetric> exceptionMeters;

        public ExceptionMeterRequestEventListener(final ImmutableMap<Method, ExceptionMeterMetric> exceptionMeters) {
            this.exceptionMeters = exceptionMeters;
        }

        @Override
        public void onEvent(RequestEvent event) {
            if (event.getType() == RequestEvent.Type.ON_EXCEPTION) {
                final ResourceMethod method = event.getUriInfo().getMatchedResourceMethod();
                final ExceptionMeterMetric metric = (method != null) ?
                        this.exceptionMeters.get(method.getInvocable().getDefinitionMethod()) : null;

                if (metric != null) {
                    if (metric.cause.isAssignableFrom(event.getException().getClass()) ||
                            (event.getException().getCause() != null &&
                                    metric.cause.isAssignableFrom(event.getException().getCause().getClass()))) {
                        Invocable invocable = event.getUriInfo().getMatchedResourceMethod().getInvocable();
                        metric.meterProvider.getMetric(invocable).mark();
                    }
                }
            }
        }
    }

    private static class ChainedRequestEventListener implements RequestEventListener {
        private final RequestEventListener[] listeners;

        private ChainedRequestEventListener(final RequestEventListener... listeners) {
            this.listeners = listeners;
        }

        @Override
        public void onEvent(final RequestEvent event) {
            for (RequestEventListener listener : listeners) {
                listener.onEvent(event);
            }
        }
    }

    @Override
    public void onEvent(ApplicationEvent event) {
        if (event.getType() == ApplicationEvent.Type.INITIALIZATION_APP_FINISHED) {
            final ImmutableMap.Builder<Method, MetricProvider<Timer>> timerBuilder = ImmutableMap.<Method, MetricProvider<Timer>>builder();
            final ImmutableMap.Builder<Method, MetricProvider<Meter>> meterBuilder = ImmutableMap.<Method, MetricProvider<Meter>>builder();
            final ImmutableMap.Builder<Method, ExceptionMeterMetric> exceptionMeterBuilder = ImmutableMap.<Method, ExceptionMeterMetric>builder();

            for (final Resource resource : event.getResourceModel().getResources()) {
                for (final ResourceMethod method : resource.getAllMethods()) {
                    registerTimedAnnotations(timerBuilder, method);
                    registerMeteredAnnotations(meterBuilder, method);
                    registerExceptionMeteredAnnotations(exceptionMeterBuilder, method);
                }

                for (final Resource childResource : resource.getChildResources()) {
                    for (final ResourceMethod method : childResource.getAllMethods()) {
                        registerTimedAnnotations(timerBuilder, method);
                        registerMeteredAnnotations(meterBuilder, method);
                        registerExceptionMeteredAnnotations(exceptionMeterBuilder, method);
                    }
                }
            }

            timers = timerBuilder.build();
            meters = meterBuilder.build();
            exceptionMeters = exceptionMeterBuilder.build();
        }
    }

    @Override
    public RequestEventListener onRequest(final RequestEvent event) {
        final RequestEventListener listener = new ChainedRequestEventListener(
                new TimerRequestEventListener(timers),
                new MeterRequestEventListener(meters),
                new ExceptionMeterRequestEventListener(exceptionMeters));

        return listener;
    }

    private void registerTimedAnnotations(final ImmutableMap.Builder<Method, MetricProvider<Timer>> builder,
                                          final ResourceMethod method) {
        final Method definitionMethod = method.getInvocable().getDefinitionMethod();
        final Timed annotation = definitionMethod.getAnnotation(Timed.class);

        if (annotation != null) {
            builder.put(definitionMethod, timerMetric(this.metrics, method, annotation));
        }
    }

    private void registerMeteredAnnotations(final ImmutableMap.Builder<Method, MetricProvider<Meter>> builder,
                                            final ResourceMethod method) {
        final Method definitionMethod = method.getInvocable().getDefinitionMethod();
        final Metered annotation = definitionMethod.getAnnotation(Metered.class);

        if (annotation != null) {
            builder.put(definitionMethod, meterMetric(metrics, method, annotation));
        }
    }

    private void registerExceptionMeteredAnnotations(final ImmutableMap.Builder<Method, ExceptionMeterMetric> builder,
                                                     final ResourceMethod method) {
        final Method definitionMethod = method.getInvocable().getDefinitionMethod();
        final ExceptionMetered annotation = definitionMethod.getAnnotation(ExceptionMetered.class);

        if (annotation != null) {
            builder.put(definitionMethod, new ExceptionMeterMetric(metrics, method, annotation));
        }
    }

    private <T extends Metric> MetricProvider<T> getMetricProvider(String name, ResourceMethod method, MetricBuilder<T> metricBuilder) {
        Map<Integer, Integer> paramToIndexMap = new HashMap<Integer, Integer>();

        Annotation[][] annotations = method.getInvocable().getDefinitionMethod().getParameterAnnotations();
        for (int paramIndex = 0; paramIndex < annotations.length; paramIndex++) {
            for (int annotationIndex = 0; annotationIndex < annotations[paramIndex].length; annotationIndex++) {
                if (annotations[paramIndex][annotationIndex].annotationType().equals(MetricNameParam.class)) {
                    MetricNameParam metricNameParam = (MetricNameParam)annotations[paramIndex][annotationIndex];
                    paramToIndexMap.put(metricNameParam.value(), paramIndex);
                }
            }
        }
        if (paramToIndexMap.size() > 0) {
            List<Integer> paramIndexList = new ArrayList<Integer>(paramToIndexMap.size());
            for (int i = 0; i < paramToIndexMap.size(); i++) {
                Integer paramIndex = paramToIndexMap.get(i);
                if (paramIndex == null) {
                    throw new IllegalArgumentException("Provided MetricNameParam values were non-contiguous");
                }
                paramIndexList.add(paramIndex);
            }
            return new ContextSensitiveMetricProvider<T>(name, metrics, metricBuilder, paramIndexList, serviceLocator);
        } else {
            return new StaticMetricProvider<T>(metricBuilder.buildMetric(metrics, name));
        }
    }

    private MetricProvider<Timer> timerMetric(final MetricRegistry registry,
                                     final ResourceMethod method,
                                     final Timed timed) {
        return timerMetric(chooseName(timed.name(), timed.absolute(), method), registry, method);
    }

    private MetricProvider<Timer> timerMetric(String name,
                                              final MetricRegistry registry,
                                              final ResourceMethod method) {
        return getMetricProvider(name, method, new MetricBuilder<Timer>() {
            @Override
            public Timer buildMetric(MetricRegistry metricRegistry, String name) {
                return registry.timer(name);
            }
        });
    }

    private MetricProvider<Meter> meterMetric(final MetricRegistry registry,
                                     final ResourceMethod method,
                                     final Metered metered) {
        return meterMetric(chooseName(metered.name(), metered.absolute(), method), registry, method);
    }

    private MetricProvider<Meter> meterMetric(String name,
                                              final MetricRegistry registry,
                                              final ResourceMethod method) {
        return getMetricProvider(name, method, new MetricBuilder<Meter>() {
            @Override
            public Meter buildMetric(MetricRegistry metricRegistry, String name) {
                return registry.meter(name);
            }
        });
    }

    protected static String chooseName(final String explicitName, final boolean absolute, final ResourceMethod method, final String... suffixes) {
        if (explicitName != null && !explicitName.isEmpty()) {
            if (absolute) {
                return explicitName;
            }
            return name(method.getInvocable().getDefinitionMethod().getDeclaringClass(), explicitName);
        }

        return name(name(method.getInvocable().getDefinitionMethod().getDeclaringClass(),
                        method.getInvocable().getDefinitionMethod().getName()),
                suffixes);
    }
}
