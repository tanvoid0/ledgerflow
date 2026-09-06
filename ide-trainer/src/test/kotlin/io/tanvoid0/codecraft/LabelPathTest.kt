package io.tanvoid0.codecraft

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same nine cases the board's own `testLabelPath()` checks. A block's
 * "Open" button exists only when the label really names a file, and getting
 * this wrong either hides the button or points it at prose.
 */
class LabelPathTest {

    private fun check(label: String, expected: String?) = assertEquals(expected, labelPath(label))

    @Test fun plainProjectRelativePath() =
        check(
            "src/main/java/io/ledgerflow/account/domain/model/Wallet.java",
            "src/main/java/io/ledgerflow/account/domain/model/Wallet.java"
        )

    @Test fun modulePrefixAndTrailingAside() =
        check("ledger: application/PlaceHold.java — the use case", "application/PlaceHold.java")

    @Test fun trailingAsideAfterAnEmDash() =
        check(
            "infra/compose/docker-compose.yml — run from inside account-service/",
            "infra/compose/docker-compose.yml"
        )

    @Test fun parentheticalIsDropped() =
        check(
            "src/main/resources/application.yml (delete application.properties)",
            "src/main/resources/application.yml"
        )

    @Test fun dotDirectoriesSurvive() =
        check(".github/workflows/schema-check.yml (sketch)", ".github/workflows/schema-check.yml")

    @Test fun commaAsideIsDropped() = check("README.md, the top of it", "README.md")

    @Test fun proseIsNotAPath() = check("Add Redis to Compose", null)

    @Test fun moreProse() = check("wrap the call in a bean you can annotate", null)

    @Test fun colonPrefixedProseIsStillProse() =
        check("Do the state exercise: remote state in S3 with locking", null)

    /** Labels carry markup, and a stripped tag must not leave a fake path behind. */
    @Test fun markupIsStrippedBeforeMatching() =
        check("pom.xml — paste inside <dependencies>", "pom.xml")

    // --- sourcePath: the label says where a human would look, the body says
    // --- where javac will.

    private val handler = "package io.ledgerflow.account.adapter.in.web;\n\nclass ApiExceptionHandler {}"

    @Test fun packageRelativeLabelGetsTheSourceRootPrefix() = assertEquals(
        "src/main/java/io/ledgerflow/account/adapter/in/web/ApiExceptionHandler.java",
        sourcePath("adapter/in/web/ApiExceptionHandler.java", handler),
    )

    /** The service prefix and the aside belong to the label, not to the path. */
    @Test fun prefixedLabelStillDerivesFromThePackage() = assertEquals(
        "src/main/java/io/ledgerflow/ledger/application/PlaceHold.java",
        sourcePath("ledger: application/PlaceHold.java — the use case",
            "package io.ledgerflow.ledger.application;\n\nclass PlaceHold {}"),
    )

    /** A label already carrying the full path must derive the same answer. */
    @Test fun fullyQualifiedLabelIsUnchanged() = assertEquals(
        "src/main/java/io/ledgerflow/account/domain/model/Wallet.java",
        sourcePath("src/main/java/io/ledgerflow/account/domain/model/Wallet.java",
            "package io.ledgerflow.account.domain.model;\n\nclass Wallet {}"),
    )

    @Test fun testsGoUnderTheTestSourceRoot() = assertEquals(
        "src/test/java/io/ledgerflow/account/AccountRepositoryIT.java",
        sourcePath("AccountRepositoryIT.java",
            "package io.ledgerflow.account;\n\nclass AccountRepositoryIT {}"),
    )

    /** No package line, or not Java at all: nothing better is known. */
    @Test fun nonJavaAndPackagelessLabelsAreLeftAlone() {
        assertEquals("pom.xml", sourcePath("pom.xml", "<project/>"))
        assertEquals("Scratch.java", sourcePath("Scratch.java", "class Scratch {}"))
        assertNull(sourcePath("some prose, not a path", "body"))
    }

    // --- a check that is an instruction, not a command

    @Test fun commentOnlyCheckIsAnObservation() {
        assertTrue(isObservation("# compare the two counts"))
        assertTrue(isObservation("# check the log\n\n# and the table"))
    }

    @Test fun aRealCommandIsNotAnObservation() {
        assertFalse(isObservation("./mvnw test"))
        assertFalse(isObservation("# start it first\n./mvnw spring-boot:run"))
    }
}
