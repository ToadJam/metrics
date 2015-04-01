package com.codahale.metrics.annotation;

/**
 * An annotation for marking a parameter in an annotated method as an argument to inject into
 * the metric name, on a per-request basis, via a call to {@code String.format()}.
 * <p/>
 * Given a method like this:
 * <pre><code>
 *     {@literal @}Timed(name = "fancyName%s")
 *     public String fancyName(@MetricNameParam(0) String name) {
 *         return "Sir Captain " + name;
 *     }
 * </code></pre>
 * <p/>
 * One or several timer metrics may be created depending on the value of the "name" parameter. For
 * example, a call to {@code fancyName("Wallace")} will result in a "fancyNameWallace" timer being
 * created and recorded. Subsequent calls to {@code fancyName("Wallace")} will result in the same
 * timer being updated, while an alternative call to {@code fancyName("Butler")} will result in
 * the creation of a second "fancyNameButler" metric. The use of multiple arguments to populate
 * the same name is also possible by modifying the {@code value} parameter to indicate the index of
 * the format specifier it refers to.
 */
@java.lang.annotation.Target({java.lang.annotation.ElementType.PARAMETER})
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface MetricNameParam {
    int value();
}
