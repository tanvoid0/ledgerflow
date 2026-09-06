package io.tanvoid0.codecraft

import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A quarter of the board's file labels are module-relative
 * ("ledger: application/PlaceHold.java"), so resolution has to work from a
 * suffix — and must refuse to guess when the suffix is ambiguous, because
 * opening the wrong file is worse than opening none.
 */
class IdeResolveTest : BasePlatformTestCase() {

    private fun ide() = Ide(project)

    fun testResolvesByPathSuffix() {
        myFixture.addFileToProject("account-service/src/main/java/io/ledgerflow/Wallet.java", "class Wallet {}")

        val vf = ide().resolve("io/ledgerflow/Wallet.java")

        assertNotNull(vf)
        assertEquals("Wallet.java", vf!!.name)
    }

    fun testAmbiguousSuffixResolvesToNothing() {
        myFixture.addFileToProject("account-service/src/main/java/app/Config.java", "class Config {}")
        myFixture.addFileToProject("ledger-service/src/main/java/app/Config.java", "class Config {}")

        assertNull(ide().resolve("app/Config.java"))
    }

    /** A file the curriculum has not created yet: the button does nothing. */
    fun testMissingFileResolvesToNothing() {
        assertNull(ide().resolve("nowhere/NotYetWritten.java"))
    }

    /** A `file` block is the whole file, so Create writes the body into it. */
    fun testCreateWritesTheBlockBody() {
        val vf = ide().createFile("svc/src/main/java/app/New.java", "class New {}")

        assertNotNull(vf)
        assertEquals("class New {}\n", String(vf!!.contentsToByteArray()))
    }

    /** A mis-click on a step already done must not wipe what that step wrote. */
    fun testCreateNeverOverwrites() {
        myFixture.addFileToProject("svc/src/main/java/app/Old.java", "class Old {}")

        val vf = ide().createFile("svc/src/main/java/app/Old.java", "REPLACED")

        assertEquals("class Old {}", String(vf!!.contentsToByteArray()))
    }

    fun testWindowsSeparatorsInTheLabelStillMatch() {
        myFixture.addFileToProject("svc/src/main/resources/application.yml", "server: {}")

        assertNotNull(ide().resolve("resources\\application.yml"))
    }

    /**
     * The learner may open the repo root or one service. A step's dir picks the
     * right module in the first case and must be a no-op in the second, where
     * the project already *is* that module.
     */
    fun testStepDirPicksTheModulesCopy() {
        myFixture.addFileToProject("account-service/pom.xml", "<project>account</project>")
        myFixture.addFileToProject("ledger-service/pom.xml", "<project>ledger</project>")

        val vf = ide().resolve("pom.xml", dir = "ledger-service")

        assertNotNull(vf)
        assertEquals("<project>ledger</project>", String(vf!!.contentsToByteArray()))
    }

    /** Ambiguous without the dir - which is the bug the dir exists to fix. */
    fun testWithoutTheStepDirTheSameLabelIsAmbiguous() {
        myFixture.addFileToProject("account-service/pom.xml", "<project>account</project>")
        myFixture.addFileToProject("ledger-service/pom.xml", "<project>ledger</project>")

        assertNull(ide().resolve("pom.xml"))
    }

    /** Project opened AT the service: the step's dir names where we already are. */
    fun testStepDirNamingTheProjectItselfIsIgnored() {
        myFixture.addFileToProject("pom.xml", "<project>here</project>")

        val vf = ide().resolve("pom.xml", dir = project.guessProjectDir()!!.name)

        assertNotNull(vf)
        assertEquals("<project>here</project>", String(vf!!.contentsToByteArray()))
    }

    /** A dir this project does not have falls back to the root, not to nothing. */
    fun testUnknownStepDirFallsBackToTheRoot() {
        myFixture.addFileToProject("pom.xml", "<project>here</project>")

        assertNotNull(ide().resolve("pom.xml", dir = "services/nope"))
    }

    /** Create puts the file in the step's module, not beside the aggregator. */
    fun testCreateHonoursTheStepDir() {
        myFixture.addFileToProject("account-service/.keep", "")

        val vf = ide().createFile("src/main/java/app/New.java", "class New {}", dir = "account-service")

        assertNotNull(vf)
        assertTrue(vf!!.path, vf.path.contains("/account-service/src/main/java/app/New.java"))
    }
}
