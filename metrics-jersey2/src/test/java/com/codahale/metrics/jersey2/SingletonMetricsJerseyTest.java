package com.codahale.metrics.jersey2;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.jersey2.resources.InstrumentedResource;
import org.glassfish.jersey.client.ClientResponse;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Test;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.codahale.metrics.MetricRegistry.name;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

/**
 * Tests registering {@link InstrumentedResourceMethodApplicationListener} as a singleton
 * in a Jersey {@link org.glassfish.jersey.server.ResourceConfig}
 */

public class SingletonMetricsJerseyTest extends JerseyTest {
    static {
        Logger.getLogger("org.glassfish.jersey").setLevel(Level.OFF);
    }

    private MetricRegistry registry;

    @Override
    protected Application configure() {
        this.registry = new MetricRegistry();

        ResourceConfig config = new ResourceConfig();
        config = config.register(new MetricsFeature(this.registry));
        config = config.register(InstrumentedResource.class);

        return config;
    }

    @Test
    public void timedMethodsAreTimed() {
        assertThat(target("timed")
                .request()
                .get(String.class))
                .isEqualTo("yay");

        final Timer timer = registry.timer(name(InstrumentedResource.class, "timed"));

        assertThat(timer.getCount()).isEqualTo(1);
    }

    @Test
    public void meteredMethodsAreMetered() {
        assertThat(target("metered")
                .request()
                .get(String.class))
                .isEqualTo("woo");

        final Meter meter = registry.meter(name(InstrumentedResource.class, "metered"));
        assertThat(meter.getCount()).isEqualTo(1);
    }

    @Test
    public void exceptionMeteredMethodsAreExceptionMetered() {
        final Meter meter = registry.meter(name(InstrumentedResource.class,
                "exceptionMetered",
                "exceptions"));

        assertThat(target("exception-metered")
                .request()
                .get(String.class))
                .isEqualTo("fuh");

        assertThat(meter.getCount()).isZero();

        try {
            target("exception-metered")
                    .queryParam("splode", true)
                    .request()
                    .get(String.class);

            failBecauseExceptionWasNotThrown(ProcessingException.class);
        } catch (ProcessingException e) {
            assertThat(e.getCause()).isInstanceOf(IOException.class);
        }

        assertThat(meter.getCount()).isEqualTo(1);
    }

    @Test
    public void customMetricNames() {
        int startingCount = registry.getTimers().entrySet().size();

        // confirm that a call using a parameter tagged with @MetricNameParam results results in
        // a new metric being produced
        assertThat(target("customMetric").queryParam("param", "foo").request().get(String.class))
                .isEqualTo("foo");
        assertThat(registry.getTimers().entrySet().size()).isEqualTo(startingCount + 1);
        assertThat(registry.getTimers().get("timedCounter[foo]").getCount()).isEqualTo(1);

        // confirm that another call with a different param value results in a second metric being produced
        assertThat(target("customMetric").queryParam("param", "bar").request().get(String.class))
                .isEqualTo("bar");
        assertThat(registry.getTimers().entrySet().size()).isEqualTo(startingCount + 2);
        assertThat(registry.getTimers().get("timedCounter[bar]").getCount()).isEqualTo(1);

        // confirm that re-issuing a call with the same parameter causes the correct metric to be properly
        // incremented but doesn't add a new one
        assertThat(target("customMetric").queryParam("param", "foo").request().get(String.class))
                .isEqualTo("foo");
        assertThat(registry.getTimers().entrySet().size()).isEqualTo(startingCount + 2);
        assertThat(registry.getTimers().get("timedCounter[foo]").getCount()).isEqualTo(2);
    }

    @Test
    public void testResourceNotFound() {
        final Response response = target().path("not-found").request().get();
        assertThat(response.getStatus()).isEqualTo(404);

        try {
            target().path("not-found").request().get(ClientResponse.class);
            failBecauseExceptionWasNotThrown(NotFoundException.class);
        } catch (NotFoundException e) {
            assertThat(e.getMessage()).isEqualTo("HTTP 404 Not Found");
        }
    }
}