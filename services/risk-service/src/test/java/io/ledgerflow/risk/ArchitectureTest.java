package io.ledgerflow.risk;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The two controls that keep this service advisory: it cannot see the ledger's or account's code, and it cannot move money itself. */
@AnalyzeClasses(packages = "io.ledgerflow.risk", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule doesNotReachIntoTheLedgerOrTheAccount = noClasses()
            .that().resideInAPackage("io.ledgerflow.risk..")
            .should().dependOnClassesThat().resideInAnyPackage("io.ledgerflow.ledger..", "io.ledgerflow.account..")
            .because("this service advises; it has no business reading another service's internals");

    @ArchTest
    static final ArchRule cannotPublishAnEventOrTouchTheOutbox = noClasses()
            .that().resideInAPackage("io.ledgerflow.risk..")
            .should().dependOnClassesThat().belongToAnyOf(
                    io.ledgerflow.starter.messaging.OutboxAppender.class,
                    org.springframework.kafka.core.KafkaTemplate.class)
            .because("the model advises; only the ledger moves money");
}
