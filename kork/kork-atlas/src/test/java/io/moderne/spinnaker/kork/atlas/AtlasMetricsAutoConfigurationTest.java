package io.moderne.spinnaker.kork.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.spectator.atlas.AtlasConfig;
import io.micrometer.atlas.AtlasMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AtlasMetricsAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AtlasMetricsAutoConfiguration.class))
          .withPropertyValues("spring.application.name=clouddriver");

  @Test
  void wiresCommonTagsFilterWhenAtlasRegistryOnClasspath() {
    contextRunner.run(
        context -> {
          assertThat(context).hasBean("moderneCommonTags");
          assertThat(context.getBean("moderneCommonTags")).isInstanceOf(MeterFilter.class);
        });
  }

  @Test
  void wiresBaseUnitCustomizerWhenAtlasRegistryOnClasspath() {
    contextRunner.run(
        context -> {
          assertThat(context).hasBean("atlasBaseUnitTagCustomizer");
          assertThat(context.getBean("atlasBaseUnitTagCustomizer"))
              .isInstanceOf(MeterRegistryCustomizer.class);
        });
  }

  @Test
  void wiresMaxMetricsGuardWhenAtlasRegistryOnClasspath() {
    contextRunner.run(
        context -> {
          assertThat(context).hasBean("atlasMaxMetricsGuard");
          assertThat(context.getBean("atlasMaxMetricsGuard"))
              .isInstanceOf(MeterRegistryCustomizer.class);
        });
  }

  @Test
  void noBeansAreWiredWhenAtlasRegistryIsAbsent() {
    contextRunner
        .withClassLoader(new FilteredClassLoader(AtlasMeterRegistry.class))
        .run(
            context -> {
              assertThat(context).doesNotHaveBean("moderneCommonTags");
              assertThat(context).doesNotHaveBean("atlasBaseUnitTagCustomizer");
              assertThat(context).doesNotHaveBean("atlasMaxMetricsGuard");
            });
  }

  @Test
  void baseUnitMeterFilter_addsSecondsToTimerWithoutExplicitUnit() {
    Meter.Id id =
        new Meter.Id("http.server.requests", Tags.empty(), null, "desc", Meter.Type.TIMER);
    Meter.Id mapped = AtlasMetricsAutoConfiguration.baseUnitMeterFilter().map(id);

    assertThat(mapped.getTag("baseUnit")).isEqualTo("seconds");
  }

  @Test
  void baseUnitMeterFilter_addsSecondsToLongTaskTimerWithoutExplicitUnit() {
    Meter.Id id = new Meter.Id("foo.long", Tags.empty(), null, "desc", Meter.Type.LONG_TASK_TIMER);
    Meter.Id mapped = AtlasMetricsAutoConfiguration.baseUnitMeterFilter().map(id);

    assertThat(mapped.getTag("baseUnit")).isEqualTo("seconds");
  }

  @Test
  void baseUnitMeterFilter_preservesExplicitBaseUnit() {
    Meter.Id id = new Meter.Id("foo.bytes", Tags.empty(), "bytes", "desc", Meter.Type.GAUGE);
    Meter.Id mapped = AtlasMetricsAutoConfiguration.baseUnitMeterFilter().map(id);

    assertThat(mapped.getTag("baseUnit")).isEqualTo("bytes");
  }

  @Test
  void baseUnitMeterFilter_doesNotAddTagToCounterWithoutBaseUnit() {
    Meter.Id id = new Meter.Id("foo.events", Tags.empty(), null, "desc", Meter.Type.COUNTER);
    Meter.Id mapped = AtlasMetricsAutoConfiguration.baseUnitMeterFilter().map(id);

    assertThat(mapped.getTag("baseUnit")).isNull();
  }

  @Test
  void maximumMetricsFilter_deniesNewMetersBeyondTheConfiguredLimit() {
    MeterFilter guard = AtlasMetricsAutoConfiguration.maximumMetricsFilter(2);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    registry.config().meterFilter(guard);

    registry.counter("meter.one");
    registry.counter("meter.two");
    registry.counter("meter.three"); // beyond the cap — must be denied, not OOM the backend

    assertThat(registry.find("meter.one").counter()).isNotNull();
    assertThat(registry.find("meter.two").counter()).isNotNull();
    assertThat(registry.find("meter.three").counter()).isNull();
  }

  @Test
  void maximumMetricsFilter_withNonPositiveLimit_disablesTheCapInsteadOfDenyingEverything() {
    // A misconfigured cap of 0 (or negative) must NOT silently drop all telemetry; it disables
    // the backstop and accepts meters as normal.
    MeterFilter guard = AtlasMetricsAutoConfiguration.maximumMetricsFilter(0);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    registry.config().meterFilter(guard);

    registry.counter("a");
    registry.counter("b");
    registry.counter("c");

    assertThat(registry.find("a").counter()).isNotNull();
    assertThat(registry.find("c").counter()).isNotNull();
  }

  @Test
  void parseMaxMetrics_fallsBackToDefaultOnBlankOrNonNumericValue() {
    assertThat(AtlasMetricsAutoConfiguration.parseMaxMetrics("100")).isEqualTo(100);
    assertThat(AtlasMetricsAutoConfiguration.parseMaxMetrics("  250 ")).isEqualTo(250);
    assertThat(AtlasMetricsAutoConfiguration.parseMaxMetrics("")).isEqualTo(50_000);
    assertThat(AtlasMetricsAutoConfiguration.parseMaxMetrics("   ")).isEqualTo(50_000);
    assertThat(AtlasMetricsAutoConfiguration.parseMaxMetrics("not-a-number")).isEqualTo(50_000);
  }

  @Test
  void maxMetricsGuard_actuallyCapsMetersWhenAppliedToAnAtlasMeterRegistry() {
    AtlasConfig nonPublishing =
        new AtlasConfig() {
          @Override
          public String get(String key) {
            return null;
          }

          @Override
          public boolean autoStart() {
            return false;
          }
        };
    AtlasMeterRegistry registry = new AtlasMeterRegistry(nonPublishing, Clock.SYSTEM);
    new AtlasMetricsAutoConfiguration().atlasMaxMetricsGuard("2").customize(registry);

    registry.counter("a");
    registry.counter("b");
    registry.counter("c"); // beyond the cap

    assertThat(registry.find("a").counter()).isNotNull();
    assertThat(registry.find("b").counter()).isNotNull();
    assertThat(registry.find("c").counter()).isNull();
    registry.close();
  }
}
