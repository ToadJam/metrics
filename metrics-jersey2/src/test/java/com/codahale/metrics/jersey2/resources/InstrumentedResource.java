package com.codahale.metrics.jersey2.resources;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Metered;
import com.codahale.metrics.annotation.MetricNameParam;
import com.codahale.metrics.annotation.Timed;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.IOException;

@Path("/")
@Produces(MediaType.TEXT_PLAIN)
public class InstrumentedResource {
    @GET
    @Timed
    @Path("/timed")
    public String timed() {
        return "yay";
    }

    @GET
    @Metered
    @Path("/metered")
    public String metered() {
        return "woo";
    }

    @GET
    @ExceptionMetered(cause = IOException.class)
    @Path("/exception-metered")
    public String exceptionMetered(@QueryParam("splode") @DefaultValue("false") boolean splode) throws IOException {
        if (splode) {
            throw new IOException("AUGH");
        }
        return "fuh";
    }

    @GET
    @Timed(name = "timed[%s]", absolute = true)
    @Path("/customMetricName")
    public String customMetric(@QueryParam("placeholder") String placeholder,
                               @MetricNameParam(0) @QueryParam("nameParam") String param) {
        return param;
    }
}
