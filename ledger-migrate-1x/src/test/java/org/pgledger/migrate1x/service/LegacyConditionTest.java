package org.pgledger.migrate1x.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * W7-BEAN-01 (principle 7.2: the condition of a bean is tested with the runner): the services of the import are the services
 * of the migration tool, so they have no condition of their own and no setting turns them on (the former
 * {@code ledger.cli.enabled} is gone with the body that shared a context with the servers). They exist exactly where
 * {@link ImportServices} is imported, which the database configuration of the tool does under its own condition (the commands
 * that need the database, with a registered driver).
 */
class LegacyConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(LegacyRowMapper.class);

    @Test
    @DisplayName("W7-BEAN-01 the row mapper of the 1.x import has no condition of its own: it is a bean of every context that has it, whatever ledger.cli.enabled says")
    void theMapperHasNoConditionOfItsOwn() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(LegacyRowMapper.class));
        runner.withPropertyValues("ledger.cli.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(LegacyRowMapper.class));
        runner.withPropertyValues("ledger.cli.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(LegacyRowMapper.class));
    }

    /** Whether the class is registered with these settings: the condition is read when it is registered, its collaborators are not needed. */
    private static boolean registered(Class<?> service, String bean, String... settings) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(settings).applyTo(context);
            context.register(service);
            return context.containsBeanDefinition(bean);
        }
    }

    @Test
    @DisplayName("W7-BEAN-01 the import service and the retire service of the 1.x ledger are registered without a setting; nothing turns them off")
    void theServicesHaveNoConditionOfTheirOwn() {
        assertThat(registered(LegacyImportService.class, "legacyImportService")).isTrue();
        assertThat(registered(LegacyImportService.class, "legacyImportService", "ledger.cli.enabled=false")).isTrue();
        assertThat(registered(LegacyRetireService.class, "legacyRetireService")).isTrue();
        assertThat(registered(LegacyRetireService.class, "legacyRetireService", "ledger.cli.enabled=false")).isTrue();
    }

    @Test
    @DisplayName("W7-BEAN-01 the services exist where ImportServices is imported, and ImportServices names exactly the import, the snapshot, the retire service and the mapper")
    void theServicesComeWithOneConfiguration() {
        assertThat(ImportServices.class.getAnnotation(Import.class).value()).containsExactlyInAnyOrder(
                LegacyImportService.class, LegacySnapshotService.class, LegacyRetireService.class, LegacyRowMapper.class);
    }
}
