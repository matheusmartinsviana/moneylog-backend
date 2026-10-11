package com.moneylog.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Regras de arquitetura do backend (D2 §3.2). O core é Java puro e não conhece a infra.
 */
@AnalyzeClasses(packages = "com.moneylog", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String CORE = "com.moneylog.core..";

    @ArchTest
    static final ArchRule coreNaoDependeDeFrameworkNemDeInfra = noClasses()
            .that().resideInAPackage(CORE)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.moneylog.infra..",
                    "org.springframework..",
                    "jakarta.persistence..",
                    "org.hibernate..",
                    "com.fasterxml..",
                    "tools.jackson..",
                    "lombok..")
            .because("o core é Java puro (BE 01, D2 §3.2)");

    /**
     * Exceção declarada ao "core sem biblioteca de terceiros" (ADR-009 §7, S-280): o frontmatter das notas é YAML e o SnakeYAML (versão da BOM
     * do Spring Boot) só pode ser usado por {@code Frontmatter}, com {@code SafeConstructor} e limites. Nada mais do core o enxerga.
     */
    @ArchTest
    static final ArchRule yamlSoNoFrontmatterDasNotas = noClasses()
            .that().resideInAPackage(CORE)
            .and().doNotHaveFullyQualifiedName("com.moneylog.core.note.domain.Frontmatter")
            .and().resideOutsideOfPackage("com.moneylog.core.note.domain")
            .should().dependOnClassesThat().resideInAPackage("org.yaml..")
            .because("SnakeYAML é a única biblioteca de terceiros do core e fica isolada em Frontmatter (ADR-009 §7)");

    @ArchTest
    static final ArchRule yamlNaoVazaDeFrontmatter = noClasses()
            .that().resideInAPackage("com.moneylog.core.note.domain")
            .and().doNotHaveFullyQualifiedName("com.moneylog.core.note.domain.Frontmatter")
            .and().doNotHaveFullyQualifiedName("com.moneylog.core.note.domain.Frontmatter$Parsed")
            .should().dependOnClassesThat().resideInAPackage("org.yaml..")
            .because("só Frontmatter fala com o SnakeYAML (ADR-009 §7)");

    /**
     * Exceção declarada (ADR-009 §7, S-280): as notas reaproveitam o mecanismo da {@code Idempotency-Key} das criações ({@code IdempotentClaim} e
     * {@code IdempotencyGateway}), que mora em {@code core.transaction} porque nasceu lá e depende de uma exceção da própria feature. Mover
     * para {@code core.sk} arrastaria {@code TransactionExceptions}; por ora é a única dependência permitida de {@code core.note} em
     * {@code core.transaction}.
     */
    @ArchTest
    static final ArchRule notasSoUsamAIdempotenciaDeTransaction = noClasses()
            .that().resideInAPackage("com.moneylog.core.note..")
            .should().dependOnClassesThat(new com.tngtech.archunit.base.DescribedPredicate<JavaClass>("estão em core.transaction e não são a idempotência") {
                @Override
                public boolean test(JavaClass type) {
                    return type.getPackageName().startsWith("com.moneylog.core.transaction")
                            && !type.getName().startsWith("com.moneylog.core.transaction.app.IdempotentClaim")
                            && !type.getName().startsWith("com.moneylog.core.transaction.app.gateways.IdempotencyGateway");
                }
            })
            .because("core.note só acopla com core.transaction pela Idempotency-Key (ADR-009 §7)");

    /** ADR-019: insight é cálculo, não IA. O motor, o contexto, o controller e a ligação dos insights não conhecem o pacote {@code ai}; a IA pode ler insights, nunca o contrário. */
    @ArchTest
    static final ArchRule insightsNaoDependemDeIa = noClasses()
            .that().resideInAnyPackage("com.moneylog.core.insights..", "com.moneylog.infra.insights..")
            .should().dependOnClassesThat().resideInAnyPackage("com.moneylog.core.ai..", "com.moneylog.infra.ai..")
            .because("insights calculados valem sem IA, sem provedor e offline (ADR-019)");

    @ArchTest
    static final ArchRule dominioDosInsightsEFuncaoPura = noClasses()
            .that().resideInAPackage("com.moneylog.core.insights.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("com.moneylog.core.insights.app..", "com.moneylog.infra..")
            .because("o domínio dos insights é função pura sobre fatos (ADR-019)");

    @ArchTest
    static final ArchRule coreNaoUsaPontoFlutuante = classes()
            .that().resideInAPackage(CORE)
            .should(naoUsarPontoFlutuante())
            .allowEmptyShould(true)
            .because("dinheiro é centavos long; nenhum double/float no core (D1 §4, invariante 2)");

    @ArchTest
    static final ArchRule featuresDoCoreSemCiclos = slices()
            .matching("com.moneylog.core.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule useCaseImplSoNoAppDoCore = classes()
            .that().haveSimpleNameEndingWith("UseCaseImpl")
            .should().resideInAPackage("com.moneylog.core.*.app..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule entityJpaSoNaPersistenceDaInfra = classes()
            .that().haveSimpleNameEndingWith("Entity")
            .should().resideInAPackage("com.moneylog.infra.*.persistence..")
            .allowEmptyShould(true);

    private static final Set<String> PONTO_FLUTUANTE = Set.of("double", "float", "java.lang.Double", "java.lang.Float");

    private static boolean isPontoFlutuante(JavaClass type) {
        return PONTO_FLUTUANTE.contains(type.getName());
    }

    private static ArchCondition<JavaClass> naoUsarPontoFlutuante() {
        return new ArchCondition<>("não usar double/float em campos, assinaturas ou chamadas") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                clazz.getFields().stream()
                        .filter(f -> isPontoFlutuante(f.getRawType()))
                        .forEach(f -> events.add(SimpleConditionEvent.violated(f, f.getFullName() + " é ponto flutuante")));
                clazz.getCodeUnits().forEach(method -> {
                    if (isPontoFlutuante(method.getRawReturnType())) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName() + " devolve ponto flutuante"));
                    }
                    method.getRawParameterTypes().stream()
                            .filter(ArchitectureTest::isPontoFlutuante)
                            .forEach(p -> events.add(SimpleConditionEvent.violated(method, method.getFullName() + " recebe ponto flutuante")));
                    method.getMethodCallsFromSelf().stream()
                            .filter(call -> isPontoFlutuante(call.getTarget().getRawReturnType()))
                            .forEach(call -> events.add(SimpleConditionEvent.violated(call, call.getDescription())));
                });
            }
        };
    }
}
