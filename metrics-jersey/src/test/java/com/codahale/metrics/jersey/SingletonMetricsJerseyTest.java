package com.codahale.metrics.jersey;

import com.sun.jersey.api.container.MappableContainerException;
import com.sun.jersey.api.core.DefaultResourceConfig;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.LowLevelAppDescriptor;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.jersey.resources.InstrumentedResource;
import org.junit.Test;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.codahale.metrics.MetricRegistry.name;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

/**
 * Tests importing {@link InstrumentedResourceMethodDispatchAdapter} as a singleton
 * in a Jersey {@link com.sun.jersey.api.core.ResourceConfig}
 */
public class SingletonMetricsJerseyTest extends JerseyTest {
    static {
        Logger.getLogger("com.sun.jersey").setLevel(Level.OFF);
    }

    private MetricRegistry registry;

    @Override
    protected AppDescriptor configure() {
        this.registry = new MetricRegistry();

        final DefaultResourceConfig config = new DefaultResourceConfig();
        config.getSingletons().add(new InstrumentedResourceMethodDispatchAdapter(registry));
        config.getClasses().add(InstrumentedResource.class);

        return new LowLevelAppDescriptor.Builder(config).build();
    }

    @Test
    public void timedMethodsAreTimed() {
        assertThat(resource().path("timed").get(String.class))
                .isEqualTo("yay");

        final Timer timer = registry.timer(name(InstrumentedResource.class, "timed"));

        assertThat(timer.getCount())
                .isEqualTo(1);
    }

    @Test
    public void meteredMethodsAreMetered() {
        assertThat(resource().path("metered").get(String.class))
                .isEqualTo("woo");

        final Meter meter = registry.meter(name(InstrumentedResource.class, "metered"));
        assertThat(meter.getCount())
                .isEqualTo(1);
    }

    @Test
    public void exceptionMeteredMethodsAreExceptionMetered() {
        final Meter meter = registry.meter(name(InstrumentedResource.class,
                                                "exceptionMetered",
                                                "exceptions"));

        assertThat(resource().path("exception-metered").get(String.class))
                .isEqualTo("fuh");

        assertThat(meter.getCount())
                .isZero();
        
        try {
            resource().path("exception-metered").queryParam("splode", "true").get(String.class);
            failBecauseExceptionWasNotThrown(MappableContainerException.class);
        } catch (MappableContainerException e) {
            assertThat(e.getCause())
                    .isInstanceOf(IOException.class);
        }

        assertThat(meter.getCount())
                .isEqualTo(1);
    }

    @Test
    public void customMetricNames() {
        int startingCount = registry.getTimers().entrySet().size();

        // confirm that a call using a parameter tagged with @MetricNameParam results results in
        // a new metric being produced
        assertThat(resource().path("customMetric").queryParam("param", "foo").get(String.class))
                .isEqualTo("foo");
        assertThat(registry.getTimers().entrySet().size()).isEqualTo(startingCount + 1);
        assertThat(registry.getTimers().get("timedCounter[foo]").getCount()).isEqualTo(1);

        // confirm that another call with a different param value results in a second metric being produced
        assertThat(resource().path("customMetric").queryParam("param", "bar").get(String.class))
                .isEqualTo("bar");
        assertThat(registry.getTimers().entrySet().size()).isEqualTo(startingCount + 2);
        assertThat(registry.getTimers().get("timedCounter[bar]").getCount()).isEqualTo(1);

        // confirm that re-issuing a call with the same parameter causes the correct metric to be properly
        // incremented but doesn't add a new one
        assertThat(resource().path("customMetric").queryParam("param", "foo").get(String.class))
                .isEqualTo("foo");
        assertThat(registry.getTimers().entrySet().size()).isEqualTo(startingCount + 2);
        assertThat(registry.getTimers().get("timedCounter[foo]").getCount()).isEqualTo(2);
    }
}
