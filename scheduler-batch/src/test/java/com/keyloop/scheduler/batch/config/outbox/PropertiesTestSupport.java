package com.keyloop.scheduler.batch.config.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Shared helpers of the outbox properties tests: build property sets and check startup failures. */
final class PropertiesTestSupport {

    private PropertiesTestSupport() {
    }

    /** Defaults with some values added or replaced, given as {@code name=value} without the prefix. */
    static ApplicationContextRunner withDefaults(ApplicationContextRunner runner, String prefix,
            List<String> defaults, String... overrides) {
        List<String> values = new ArrayList<>(defaults);
        for (String override : overrides) {
            values.add(prefix + override);
        }
        return runner.withPropertyValues(values.toArray(String[]::new));
    }

    /** Defaults with the given keys (without the prefix) removed. */
    static ApplicationContextRunner without(ApplicationContextRunner runner, String prefix,
            List<String> defaults, String... keys) {
        Set<String> removed = Stream.of(keys).map(key -> prefix + key).collect(Collectors.toSet());
        String[] kept = defaults.stream()
                .filter(p -> !removed.contains(p.substring(0, p.indexOf('='))))
                .toArray(String[]::new);
        return runner.withPropertyValues(kept);
    }

    static void assertFailsNaming(AssertableApplicationContext context, String... texts) {
        assertThat(context).hasFailed();
        String message = fullMessage(context.getStartupFailure());
        for (String text : texts) {
            assertThat(message).contains(text);
        }
    }

    private static String fullMessage(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t).append('\n');
        }
        return text.toString();
    }
}
