package com.enterprise.ai.control.a2a.infrastructure.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.io.ResolverUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class A2aMapperAnnotationSqlContractTest {

    private static final String PERSISTENCE_PACKAGE =
            "com.enterprise.ai.control.a2a.infrastructure.persistence";

    @Test
    void staticAnnotationSqlMustNotLeakXmlEntitiesToTheDatabase() {
        ResolverUtil<Class<?>> resolver = new ResolverUtil<>();
        resolver.find(new ResolverUtil.AnnotatedWith(Mapper.class), PERSISTENCE_PACKAGE);

        assertThat(resolver.getClasses()).isNotEmpty();
        resolver.getClasses().stream()
                .flatMap(mapper -> Arrays.stream(mapper.getDeclaredMethods()))
                .flatMap(this::annotationSql)
                .filter(sql -> !sql.stripLeading().startsWith("<script>"))
                .forEach(sql -> assertThat(sql)
                        .as("Static MyBatis annotation SQL must use SQL operators, not XML entities")
                        .doesNotContain("&lt;", "&gt;", "&amp;"));
    }

    private Stream<String> annotationSql(Method method) {
        Stream.Builder<String> sql = Stream.builder();
        add(sql, method.getAnnotation(Select.class));
        add(sql, method.getAnnotation(Insert.class));
        add(sql, method.getAnnotation(Update.class));
        add(sql, method.getAnnotation(Delete.class));
        return sql.build();
    }

    private void add(Stream.Builder<String> sql, Select annotation) {
        if (annotation != null) {
            sql.add(String.join(" ", annotation.value()));
        }
    }

    private void add(Stream.Builder<String> sql, Insert annotation) {
        if (annotation != null) {
            sql.add(String.join(" ", annotation.value()));
        }
    }

    private void add(Stream.Builder<String> sql, Update annotation) {
        if (annotation != null) {
            sql.add(String.join(" ", annotation.value()));
        }
    }

    private void add(Stream.Builder<String> sql, Delete annotation) {
        if (annotation != null) {
            sql.add(String.join(" ", annotation.value()));
        }
    }
}
