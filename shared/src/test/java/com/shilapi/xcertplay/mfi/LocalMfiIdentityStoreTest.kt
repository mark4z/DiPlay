package com.shilapi.xcertplay.mfi

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.pkcs.ContentInfo
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.SignedData
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMfiIdentityStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Identity(val key: ByteArray, val certificate: ByteArray)

    private fun identity(curve: String = "secp256r1"): Identity {
        // Runtime-generated synthetic material only, never a real accessory identity.
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(curve))
        val pair = generator.generateKeyPair()
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=DiPlay synthetic import test only")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.ONE))
            setSignature(algorithm)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(0)))
            setEndDate(Time(Date(4102444800000L)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private)
        signer.update(tbs.encoded)
        val certificate = DERSequence(arrayOf<ASN1Encodable>(tbs, algorithm, DERBitString(signer.sign())))
        val pkcs7 = ContentInfo(PKCSObjectIdentifiers.signedData, SignedData(
            ASN1Integer(1), DERSet(), ContentInfo(PKCSObjectIdentifiers.data, null),
            DERSet(certificate), null, DERSet(),
        )).encoded
        return Identity(pair.private.encoded, pkcs7)
    }

    private open class TestFiles : LocalMfiIdentityStore.FileOperations {
        override fun syncDirectory(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private class ProcessDeath : Error()

    private fun store(root: File, files: TestFiles = TestFiles()) = LocalMfiIdentityStore(root, files)
    private fun live(root: File) = File(root, LocalMfiAuthenticationClient.DIRECTORY)

    private fun installLegacy(root: File, identity: Identity) {
        check(live(root).mkdir())
        File(live(root), "identity.pk8").writeBytes(identity.key)
        File(live(root), "certificate.p7b").writeBytes(identity.certificate)
    }

    private fun stage(store: LocalMfiIdentityStore, identity: Identity): LocalMfiIdentityStore.ImportSession =
        store.beginImport().apply {
            identity.key.inputStream().use(::writePrivateKey)
            identity.certificate.inputStream().use(::writeCertificate)
            validate()
        }

    private fun assertIdentity(root: File, expected: Identity) {
        assertArrayEquals(expected.key, File(live(root), "identity.pk8").readBytes())
        assertArrayEquals(expected.certificate, LocalMfiAuthenticationClient.load(live(root)).readCertificate())
    }

    private fun assertNoTransactions(root: File) {
        assertEquals(listOf(LocalMfiAuthenticationClient.DIRECTORY), root.list()!!.sorted())
    }

    @Test fun importsIntoPrivateStorageAndSurvivesReloadWithoutAssets() {
        val root = temporary.newFolder()
        val expected = identity()
        stage(store(root), expected).use { it.commit() }
        val reloaded = store(root)
        assertTrue(reloaded.hasImportedIdentity())
        reloaded.ensureInstalled { throw AssertionError("Imported identity must precede assets") }
        assertIdentity(root, expected)
        assertNoTransactions(root)
        val forbidden = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
        for (file in listOf(live(root)) + live(root).listFiles()!!.toList()) {
            assertTrue(java.nio.file.Files.getPosixFilePermissions(file.toPath()).intersect(forbidden).isEmpty())
        }
    }

    @Test fun installedAssetsAreFallbackAndImportedPairOverridesThem() {
        val root = temporary.newFolder()
        val bundled = identity()
        val store = store(root)
        store.ensureInstalled { name ->
            (if (name == "identity.pk8") bundled.key else bundled.certificate).inputStream()
        }
        assertFalse(store.hasImportedIdentity())
        assertIdentity(root, bundled)
        val imported = identity()
        stage(store, imported).use { it.commit() }
        store.ensureInstalled { throw AssertionError("Do not overwrite imported pair") }
        assertTrue(store.hasImportedIdentity())
        assertIdentity(root, imported)
    }

    @Test fun cancelledFirstOrSecondPickerLeavesExistingPairAndRemovesStaging() {
        val root = temporary.newFolder()
        val old = identity()
        installLegacy(root, old)
        val store = store(root)
        store.beginImport().close()
        store.beginImport().use { session -> session.writePrivateKey(identity().key.inputStream()) }
        stage(store, identity()).close()
        assertIdentity(root, old)
        assertFalse(store.hasImportedIdentity())
        assertNoTransactions(root)
    }

    @Test fun rejectsEmptyOversizeMalformedWrongCurveAndMismatchedInputsWithoutReplacingOldPair() {
        val root = temporary.newFolder()
        val old = identity()
        installLegacy(root, old)
        val store = store(root)
        for (value in listOf(ByteArray(0), ByteArray(16 * 1024 + 1))) {
            store.beginImport().use { session ->
                assertThrows(IllegalArgumentException::class.java) { session.writePrivateKey(value.inputStream()) }
                assertThrows(IllegalArgumentException::class.java) { session.writeCertificate(value.inputStream()) }
            }
        }
        for (bad in listOf(Identity(byteArrayOf(1, 2), old.certificate),
            Identity(old.key, byteArrayOf(1, 2)), Identity(identity().key, old.certificate),
            identity("secp384r1"))) {
            store.beginImport().use { session ->
                session.writePrivateKey(bad.key.inputStream())
                session.writeCertificate(bad.certificate.inputStream())
                assertThrows(Exception::class.java) { session.validate() }
                assertThrows(IllegalStateException::class.java) { session.commit() }
            }
            assertIdentity(root, old)
        }
        assertNoTransactions(root)
    }

    @Test fun failedOrChangedInputCannotReusePriorValidationAndDoesNotCloseCallerStream() {
        val root = temporary.newFolder()
        val old = identity()
        installLegacy(root, old)
        stage(store(root), identity()).use { session ->
            var closed = false
            val input = object : ByteArrayInputStream(old.key) {
                override fun close() { closed = true; super.close() }
            }
            session.writePrivateKey(input)
            assertFalse(closed)
            assertThrows(IllegalStateException::class.java) { session.commit() }
            val broken = object : InputStream() {
                override fun read(): Int = throw IOException("Synthetic input failure")
            }
            assertThrows(IOException::class.java) { session.writeCertificate(broken) }
            assertThrows(IllegalStateException::class.java) { session.commit() }
        }
        assertIdentity(root, old)
        assertNoTransactions(root)
    }

    @Test fun legacyPairSurvivesFailureOfVeryFirstRename() {
        val root = temporary.newFolder()
        val old = identity()
        installLegacy(root, old)
        val failing = object : TestFiles() {
            override fun rename(from: File, to: File) = false
        }
        stage(store(root, failing), identity()).use { session ->
            assertThrows(IllegalStateException::class.java) { session.commit() }
        }
        assertIdentity(root, old)
        assertNoTransactions(root)
    }

    @Test fun failedPublicationRenameRestoresTheLegacyPair() {
        val root = temporary.newFolder()
        val old = identity()
        installLegacy(root, old)
        val failing = object : TestFiles() {
            override fun rename(from: File, to: File): Boolean =
                if (from.name == "offline-mfi-pending") false else super.rename(from, to)
        }
        stage(store(root, failing), identity()).use { session ->
            assertThrows(IllegalStateException::class.java) { session.commit() }
        }
        assertIdentity(root, old)
        assertNoTransactions(root)
    }

    @Test fun oneShotDirectorySyncFailureAtEveryRenameRestoresThePreviousPair() {
        for (failAt in 1..4) {
            val root = temporary.newFolder()
            val old = identity()
            installLegacy(root, old)
            var commitSyncs = 0
            val failing = object : TestFiles() {
                override fun syncDirectory(directory: File) {
                    // Staging validation happens first; count only commit's root/live syncs.
                    if ((directory == root || directory == live(root)) && ++commitSyncs == failAt) {
                        throw IOException("Synthetic directory sync failure")
                    }
                    super.syncDirectory(directory)
                }
            }
            stage(store(root, failing), identity()).use { session ->
                assertThrows(IOException::class.java) { session.commit() }
            }
            store(root).recover()
            assertIdentity(root, old)
            assertNoTransactions(root)
        }
    }

    @Test fun failedRollbackRetainsBackupForTheNextRecovery() {
        val root = temporary.newFolder()
        val old = identity()
        installLegacy(root, old)
        val failing = object : TestFiles() {
            override fun rename(from: File, to: File): Boolean =
                if (from.name == "offline-mfi-previous") false else super.rename(from, to)
            override fun checkpoint(checkpoint: LocalMfiIdentityStore.Checkpoint) {
                if (checkpoint == LocalMfiIdentityStore.Checkpoint.REPLACEMENT_MOVED) {
                    throw IOException("Synthetic publication failure")
                }
            }
        }
        stage(store(root, failing), identity()).use { session ->
            val failure = assertThrows(IOException::class.java) { session.commit() }
            assertEquals(1, failure.suppressed.size)
        }
        assertTrue(File(root, "offline-mfi-previous").isDirectory)
        store(root).recover()
        assertIdentity(root, old)
        assertNoTransactions(root)
    }

    @Test fun corruptedCommittedReplacementFallsBackToRetainedPreviousPair() {
        val root = temporary.newFolder()
        val old = identity()
        installLegacy(root, old)
        val crashing = object : TestFiles() {
            override fun checkpoint(checkpoint: LocalMfiIdentityStore.Checkpoint) {
                if (checkpoint == LocalMfiIdentityStore.Checkpoint.COMMITTED) throw ProcessDeath()
            }
        }
        val session = stage(store(root, crashing), identity())
        assertThrows(ProcessDeath::class.java) { session.commit() }
        File(live(root), "identity.pk8").writeText("synthetic corruption")
        store(root).recover()
        assertIdentity(root, old)
        assertNoTransactions(root)
        session.close()
    }

    @Test fun eachInjectedFailureBeforeCommitRollsBackToPreviousPair() {
        for (point in LocalMfiIdentityStore.Checkpoint.entries.filter { it != LocalMfiIdentityStore.Checkpoint.COMMITTED }) {
            val root = temporary.newFolder()
            val old = identity()
            installLegacy(root, old)
            val failing = object : TestFiles() {
                override fun checkpoint(checkpoint: LocalMfiIdentityStore.Checkpoint) {
                    if (point == checkpoint) throw IOException("Synthetic transaction failure")
                }
            }
            stage(store(root, failing), identity()).use { session ->
                assertThrows(IOException::class.java) { session.commit() }
            }
            store(root).recover()
            assertIdentity(root, old)
            assertNoTransactions(root)
        }
    }

    @Test fun recoveryAtEveryProcessDeathBoundaryKeepsExactlyOneCompletePair() {
        for (point in LocalMfiIdentityStore.Checkpoint.entries) {
            val root = temporary.newFolder()
            val old = identity()
            val replacement = identity()
            installLegacy(root, old)
            val crashing = object : TestFiles() {
                override fun checkpoint(checkpoint: LocalMfiIdentityStore.Checkpoint) {
                    if (point == checkpoint) throw ProcessDeath()
                }
            }
            val session = stage(store(root, crashing), replacement)
            assertThrows(ProcessDeath::class.java) { session.commit() }
            val recovered = store(root)
            recovered.recover()
            recovered.recover() // Recovery is idempotent.
            val committed = point == LocalMfiIdentityStore.Checkpoint.COMMITTED
            assertIdentity(root, if (committed) replacement else old)
            assertEquals(committed, recovered.hasImportedIdentity())
            assertNoTransactions(root)
            session.close()
        }
    }

    @Test fun interruptedFirstInstallationDoesNotBecomeAnAccidentalImport() {
        for (point in LocalMfiIdentityStore.Checkpoint.entries) {
            val root = temporary.newFolder()
            val replacement = identity()
            val crashing = object : TestFiles() {
                override fun checkpoint(checkpoint: LocalMfiIdentityStore.Checkpoint) {
                    if (point == checkpoint) throw ProcessDeath()
                }
            }
            val session = stage(store(root, crashing), replacement)
            assertThrows(ProcessDeath::class.java) { session.commit() }
            val recovered = store(root)
            recovered.recover()
            if (point == LocalMfiIdentityStore.Checkpoint.COMMITTED) {
                assertIdentity(root, replacement)
                assertTrue(recovered.hasImportedIdentity())
            } else {
                assertFalse(live(root).exists())
                assertFalse(recovered.hasImportedIdentity())
                assertTrue(root.listFiles()!!.isEmpty())
            }
            session.close()
        }
    }

    @Test fun recoveryDoesNotRemoveActivePickerButRemovesAbandonedStages() {
        val root = temporary.newFolder()
        val store = store(root)
        val replacement = identity()
        stage(store, replacement).use { session ->
            val abandoned = File(root, "offline-mfi-import-abandoned").apply { mkdir() }
            File(abandoned, "identity.pk8").writeText("synthetic partial input")
            val legacy = File(root, "offline-mfi-staging").apply { mkdir() }
            store(root).recover()
            assertFalse(abandoned.exists())
            assertFalse(legacy.exists())
            session.commit()
        }
        assertIdentity(root, replacement)
        assertNoTransactions(root)
    }

    @Test fun closeDuringBlockedCopyCannotPublishPartialInput() {
        val root = temporary.newFolder()
        val old = identity()
        installLegacy(root, old)
        val session = store(root).beginImport()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val input = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return -1
            }
        }
        val writer = Thread {
            try { session.writePrivateKey(input) } catch (error: Throwable) { failure.set(error) }
        }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = Thread { session.close() }.apply { start() }
        release.countDown()
        writer.join(5000)
        closer.join(5000)
        assertFalse(writer.isAlive)
        assertFalse(closer.isAlive)
        assertNotNull(failure.get())
        assertThrows(IllegalStateException::class.java) { session.commit() }
        assertIdentity(root, old)
        assertNoTransactions(root)
    }
}
