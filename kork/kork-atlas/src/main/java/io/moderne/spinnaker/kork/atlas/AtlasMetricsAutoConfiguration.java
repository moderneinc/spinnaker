package io.moderne.spinnaker.kork.atlas;

import io.micrometer.atlas.AtlasMeterRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

/**
 * Wires Micrometer common tags and an Atlas-specific {@code baseUnit} tag customizer for forked
 * Spinnaker JVM services.
 *
 * <p>Atlas URI / step / batch size flow through Spring Boot's built-in {@code
 * management.atlas.metrics.export.*} keys. This module ships with {@code enabled=false} as the
 * default; the deploy mechanism enables publishing by setting {@code MODERNE_ATLAS_URI} and {@code
 * management.atlas.metrics.export.enabled=true} together.
 */
@AutoConfiguration
@ConditionalOnClass(AtlasMeterRegistry.class)
@PropertySource("classpath:kork-atlas.properties")
public class AtlasMetricsAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AtlasMetricsAutoConfiguration.class);
  static final int DEFAULT_MAX_METRICS = 50_000;

  @Bean
  MeterFilter moderneCommonTags(
      @Value("${spring.application.name:unknown}") String applicationName) {
    return MeterFilter.commonTags(Ec2CommonTags.derive(applicationName));
  }

  @Bean
  MeterRegistryCustomizer<AtlasMeterRegistry> atlasBaseUnitTagCustomizer() {
    return registry -> registry.config().meterFilter(baseUnitMeterFilter());
  }

  /**
   * Defensive backstop: cap the number of distinct meters the Atlas registry will hold so a runaway
   * high-cardinality source degrades gracefully (new meters denied) instead of growing the publish
   * set until the JVM OOMs. Tune via {@code moderne.atlas.max-metrics}; a non-positive value
   * disables the cap rather than denying every meter.
   */
  @Bean
  MeterRegistryCustomizer<AtlasMeterRegistry> atlasMaxMetricsGuard(
      @Value("${moderne.atlas.max-metrics:50000}") String maxMetrics) {
    return registry ->
        registry.config().meterFilter(maximumMetricsFilter(parseMaxMetrics(maxMetrics)));
  }

  static int parseMaxMetrics(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException | NullPointerException e) {
      log.warn(
          "Invalid moderne.atlas.max-metrics '{}'; using default {}", value, DEFAULT_MAX_METRICS);
      return DEFAULT_MAX_METRICS;
    }
  }

  static MeterFilter maximumMetricsFilter(int maxMetrics) {
    if (maxMetrics <= 0) {
      // A cap of 0 would deny the very first meter and silently zero out all telemetry. Treat any
      // non-positive value as "disabled" so a misconfiguration degrades to no-cap, not no-metrics.
      log.warn(
          "moderne.atlas.max-metrics={} is non-positive; Atlas meter cap disabled", maxMetrics);
      return new MeterFilter() {};
    }
    return MeterFilter.maximumAllowableMetrics(maxMetrics);
  }

  static MeterFilter baseUnitMeterFilter() {
    return new MeterFilter() {
      @Override
      public Meter.Id map(Meter.Id id) {
        String unit = id.getBaseUnit();
        if (unit == null || unit.isEmpty()) {
          if (id.getType() == Meter.Type.TIMER || id.getType() == Meter.Type.LONG_TASK_TIMER) {
            unit = "seconds"; // matches AtlasMeterRegistry.baseTimeUnit()
          } else {
            return id;
          }
        }
        return id.withTag(Tag.of("baseUnit", unit));
      }
    };
  }
}
