package com.pulseops.common.web;

import java.util.Locale;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API enums are documented in UPPER_SNAKE case but accepted case-insensitively in query
 * parameters (JSON bodies: spring.jackson.mapper.accept-case-insensitive-enums).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new CaseInsensitiveEnumConverterFactory());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class CaseInsensitiveEnumConverterFactory implements ConverterFactory<String, Enum> {

        @Override
        public <T extends Enum> Converter<String, T> getConverter(Class<T> targetType) {
            return source -> {
                String value = source.trim();
                if (value.isEmpty()) {
                    return null;
                }
                return (T) Enum.valueOf(targetType, value.toUpperCase(Locale.ROOT));
            };
        }
    }
}
