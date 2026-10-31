package dev.finflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.finflow.ledger.LedgerService;
import dev.finflow.ledger.domain.AccountBalance;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages="dev.finflow",importOptions=ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule moduleBoundaries=classes().should(new ModuleBoundaryCondition("dev.finflow."));

    @ArchTest
    static final ArchRule controllersDoNotAccessPersistence=noClasses().that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().resideInAnyPackage("..repository..");

    @ArchTest
    static final ArchRule controllersDoNotExposeEntities=noClasses().that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class);

    @ArchTest
    static final ArchRule controllersAreNotTransactional=noClasses().that().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class);

    @ArchTest
    static final ArchRule controllerMethodsAreNotTransactional=noMethods().that()
            .areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class);

    @ArchTest
    static final ArchRule entitiesStayInternal=classes().that().areAnnotatedWith(Entity.class)
            .should().resideInAnyPackage("..domain..","dev.finflow.common..");

    @ArchTest
    static final ArchRule ledgerInternalsStayInLedger=noClasses().that().resideOutsideOfPackage("dev.finflow.ledger..")
            .should().dependOnClassesThat().resideInAnyPackage("dev.finflow.ledger.domain..","dev.finflow.ledger.repository..");

    @ArchTest
    static final ArchRule onlyLedgerServiceChangesBalances=noClasses().that()
            .doNotHaveFullyQualifiedName(LedgerService.class.getName())
            .should().callMethod(AccountBalance.class,"apply",BigDecimal.class,Instant.class);

    @ArchTest
    static final ArchRule domainHasNoTransportOrPersistenceDependencies=noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..repository..","..service..","org.springframework.web..",
                    "org.springframework.kafka..","org.springframework.jdbc..");


    @ArchTest
    static final ArchRule ledgerPostingRequiresBusinessTransaction=mandatoryPosting(LedgerService.class);

    @ArchTest
    static final ArchRule ledgerAdapterRequiresBusinessTransaction=mandatoryPosting(
            dev.finflow.ledger.service.LedgerOperationsAdapter.class);

    private static ArchRule mandatoryPosting(Class<?> owner) {
        return methods().that().areDeclaredIn(owner).and().haveName("post").should(
                new com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>(
                        "require an existing business transaction") {
                    @Override
                    public void check(com.tngtech.archunit.core.domain.JavaMethod method,
                            com.tngtech.archunit.lang.ConditionEvents events) {
                        boolean valid=method.isAnnotatedWith(Transactional.class)
                                && method.getAnnotationOfType(Transactional.class).propagation()
                                ==org.springframework.transaction.annotation.Propagation.MANDATORY;
                        events.add(new com.tngtech.archunit.lang.SimpleConditionEvent(method,valid,
                                method.getFullName()+" must use Propagation.MANDATORY"));
                    }
                });
    }
}
