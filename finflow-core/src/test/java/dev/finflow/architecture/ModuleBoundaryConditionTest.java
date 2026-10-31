package dev.finflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import architecture.fixtures.consumer.InvalidConsumer;
import architecture.fixtures.consumer.ValidConsumer;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ModuleBoundaryConditionTest {
    @Test
    void detectsAccessToAnotherModulesInternalClass() {
        var imported=new ClassFileImporter().importClasses(InvalidConsumer.class);
        var result=classes().should(new ModuleBoundaryCondition("architecture.fixtures.")).evaluate(imported);
        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails()).anyMatch(
                detail -> detail.contains("producer.internal.PrivateRepository"));
    }

    @Test
    void permitsPublishedInterfaces() {
        var imported=new ClassFileImporter().importClasses(ValidConsumer.class);
        var result=classes().should(new ModuleBoundaryCondition("architecture.fixtures.")).evaluate(imported);
        assertThat(result.hasViolation()).isFalse();
    }
}
