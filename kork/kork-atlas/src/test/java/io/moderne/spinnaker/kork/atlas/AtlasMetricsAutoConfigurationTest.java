package io.moderne.spinnaker.kork.atlas;

import io.micrometer.atlas.AtlasMeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AtlasMetricsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtlasMetricsAutoConfiguration.class))
            .withPropertyValues("spring.application.name=clouddriver");

    @Test
    void wiresCommonTagsFilterWhenAtlasRegistryOnClasspath() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("moderneCommonTags");
            assertThat(context.getBean("moderneCommonTags")).isInstanceOf(MeterFilter.class);
        });
    }

    @Test
    void wiresBaseUnitCustomizerWhenAtlasRegistryOnClasspath() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("atlasBaseUnitTagCustomizer");
            assertThat(context.getBean("atlasBaseUnitTagCustomizer"))
                    .isInstanceOf(MeterRegistryCustomizer.class);
        });
    }
}
