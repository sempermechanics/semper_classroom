package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.data.cloud.CorruptTransferException
import com.sempermechanics.semper.data.net.ArtifactRoles
import com.sempermechanics.semper.util.AtomicFiles
import com.sempermechanics.semper.util.Digests
import com.sempermechanics.semper.util.Zips
import com.sempermechanics.semper.util.crc32
import com.sempermechanics.semper.util.writeVia
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Session.zip build / verify helpers shared by upload.
 *
 * Large binaries (TIFF / DAT / PNG / GIF / …) are [ZipEntry.STORED].
 *
 * Do **not** toggle [ZipOutputStream.setLevel] between [Deflater.NO_COMPRESSION]
 * and [Deflater.DEFAULT_COMPRESSION] on Android: a Device Session.zip from Drive
 * had a valid central directory / sha256, but the first entry after each
 * level-0 to level-9 switch (raw TIFF, then Exx animation GIF) carried a
 * 7685-byte junk prefix before a good deflate stream — inflate then fails
 * with "invalid stored block lengths" or "invalid code lengths set".
 * Desktop OpenJDK does not reproduce; Pixel-class Android Deflater does.
 * Textable payloads (csv/json) stay DEFLATED at a single level.
 */
@Suppress("TooManyFunctions") // build / verify / STORED+DEFLATED entry writers stay together
internal object SessionZip {

    data class Member(val role: String, val name: String, val file: File)

    /** Fixed name of the reference image in the archive. */
    const val REFERENCE_NAME = "Reference.png"

    /**
     * The archive folder that holds only the reference, so no deformed frame can
     * share its entry name: frames keep the user's own file name under `raw/`,
     * and one may well be called `Reference.png`. An archive folder, never a
     * role the backend sees: only `metadata`, `bundle` and `extras` are declared.
     * Written since `metadata.json` schema 4
     * ([com.sempermechanics.semper.data.cloud.SessionUploadMetadata.SCHEMA]).
     */
    const val REFERENCE_ROLE = "ref"

    /**
     * The reference's entry name since schema 4: `ref/Reference.png`. Archives
     * before it kept the reference at `raw/Reference.png`, beside the frames; a
     * restore reads that as the reference only when the archive has no entry here
     * (`RestoreUnpacker.destFor`), and otherwise as a frame of that name.
     */
    val REFERENCE_ENTRY: String = entryName(REFERENCE_ROLE, REFERENCE_NAME)

    /**
     * Whether a [role] goes in `Session.zip`, the restore-essential bundle: every original
     * image (`ref/` the reference, `raw/` the deformed originals) plus the engine results
     * (`dat/`, which also carries the run's `record_manifest.json`, ADR-028: the backend
     * validates roles, so the manifest has none of its own). That restores everything a
     * local run produced, re-export and report included, without a second fetch.
     *
     * `csv`, `reports` and `processed` are derived and regenerated on export
     * (`SessionEverythingExporter`), so they alone go to `Extras.zip`. Keep
     * [RESTORE_ENTRY_PREFIXES] in step with this.
     */
    fun isRestoreEssential(role: String): Boolean =
        role == ArtifactRoles.DAT || role == ArtifactRoles.RAW || role == REFERENCE_ROLE

    /**
     * Entry-name prefixes a restore fetches from a **legacy** single-archive backup:
     * [isRestoreEssential]'s roles as zip-entry prefixes, for the ranged-prefix reader.
     * (A legacy archive predates `ref/`; it is listed to keep the two sets in step.)
     */
    val RESTORE_ENTRY_PREFIXES: Set<String> = setOf("$REFERENCE_ROLE/", "raw/", "dat/")

    /** Already-compressed or large binary payloads — store, do not deflate. */
    val STORE_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "pdf", "webp", "zip", "gif", "bmp",
        "tif", "tiff", "dat",
    )

    fun entryName(role: String, name: String): String = "$role/$name"

    fun shouldStore(fileName: String): Boolean =
        fileName.substringAfterLast('.').lowercase(Locale.US) in STORE_EXTENSIONS

    /**
     * `.dat` entries are [DatCodec]-decoded on every read ([readEntry], [copyEntry]; a raw legacy
     * entry passes through) but encoded on write only when [build] gets `encodeDatEntries = true`,
     * which its callers take from [com.sempermechanics.semper.data.net.AppRemoteConfig.datCodecEncodingEnabled].
     * The rollout is backend-gated (`backend/app/config.py` `DAT_CODEC_ENCODING_ENABLED`) because a
     * client older than the codec writes a restored `.dat` to disk undecoded.
     */
    private fun isDatEntry(fileName: String): Boolean =
        fileName.substringAfterLast('.').equals("dat", ignoreCase = true)

    /**
     * Write [members] to [out] (via `*.tmp` + rename). Returns lowercase sha256
     * of the finished archive. Verifies every entry round-trips before promote.
     *
     * @param onBytes invoked with source bytes written into the archive (not
     * the CRC pre-pass for STORED entries). Used for Home "preparing %" on
     * large PLC bundles where zip dominates prepare time.
     * @param encodeDatEntries [DatCodec]-encode `.dat` members; see [isDatEntry].
     */
    fun build(
        members: List<Member>,
        out: File,
        onBytes: (Long) -> Unit = {},
        encodeDatEntries: Boolean = false,
    ): String {
        require(members.isNotEmpty()) { "Session.zip payload is empty" }
        val digest = Digests.sha256()
        AtomicFiles.writeVia(out, tmp = File(out.parentFile, "${out.name}.tmp"), clearDest = true) { tmp ->
            val storedCrcs = writeArchive(tmp, members, digest, onBytes, encodeDatEntries)
            verifyRoundTrip(tmp, members, encodeDatEntries, storedCrcs)
        }
        Timber.i("Bundled %d artifacts into %s (%d bytes)", members.size, out.name, out.length())
        return Digests.toHex(digest.digest())
    }

    /**
     * Stream every entry via [ZipFile] (not [java.util.zip.ZipInputStream]).
     * [onEntry] must fully consume [input] before returning.
     */
    fun forEachEntry(
        zip: File,
        onEntry: (role: String, name: String, input: InputStream) -> Unit,
    ) {
        ZipFile(zip).use { zf ->
            zf.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                readEntry(zf, entry, onEntry)
            }
        }
    }

    private fun writeArchive(
        tmp: File,
        members: List<Member>,
        digest: java.security.MessageDigest,
        onBytes: (Long) -> Unit,
        encodeDatEntries: Boolean,
    ): Map<String, Long> {
        val storedCrcs = mutableMapOf<String, Long>()
        DigestOutputStream(BufferedOutputStream(tmp.outputStream()), digest).use { digOut ->
            ZipOutputStream(digOut).use { zip ->
                members.forEach { putMember(zip, it, onBytes, encodeDatEntries, storedCrcs) }
            }
        }
        return storedCrcs
    }

    /**
     * Concatenate [sources] into one archive at [out], first source winning on a
     * duplicate entry name.
     *
     * Used by "Save to Files" to hand over `Session.zip` and `Extras.zip` as one archive.
     * Each entry keeps its compression method, and [ZipOutputStream.setLevel] is never
     * called (see the class comment on toggling levels).
     */
    fun merge(sources: List<File>, out: File) {
        require(sources.isNotEmpty()) { "merge needs at least one source" }
        AtomicFiles.writeVia(out, tmp = File(out.parentFile, "${out.name}.merge"), clearDest = true) { tmp ->
            ZipOutputStream(BufferedOutputStream(tmp.outputStream())).use { zos ->
                val seen = HashSet<String>()
                for (source in sources) {
                    copyEntriesInto(source, zos, seen)
                }
            }
        }
    }

    /** Copy every not-yet-[seen] entry of [source] into [zos], preserving its method. */
    private fun copyEntriesInto(source: File, zos: ZipOutputStream, seen: MutableSet<String>) {
        ZipFile(source).use { zf ->
            zf.entries().asSequence()
                .filterNot { it.isDirectory }
                .filter { seen.add(it.name) }
                .forEach { entry -> copyEntry(zf, entry, zos) }
        }
    }

    /** Copy one entry verbatim, keeping its compression method and STORED sizes. */
    private fun copyEntry(from: ZipFile, entry: ZipEntry, zos: ZipOutputStream) {
        val fileName = entry.name.substringAfterLast('/')
        if (isDatEntry(fileName)) {
            // Save to Files hands the user a usable archive, so an encoded .dat is decoded on
            // the way out; one that will not decode is a corrupt transfer, as on restore.
            Zips.putStoredBytes(zos, entry.name, decodeDatEntryOrThrow(from, entry))
            return
        }
        val copy = ZipEntry(entry.name).apply {
            method = entry.method
            if (entry.method == ZipEntry.STORED) {
                size = entry.size
                compressedSize = entry.compressedSize
                crc = entry.crc
            }
            if (entry.time >= 0L) time = entry.time
        }
        zos.putNextEntry(copy)
        from.getInputStream(entry).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    private fun readEntry(
        zf: ZipFile,
        entry: ZipEntry,
        onEntry: (role: String, name: String, input: InputStream) -> Unit,
    ) {
        val role = entry.name.substringBefore('/', missingDelimiterValue = "")
        val name = entry.name.substringAfter('/', missingDelimiterValue = entry.name)
        try {
            if (isDatEntry(name)) {
                // .dat is small enough to buffer whole (a few MB even at LARGE scale)
                // — everything else keeps streaming straight through, unbuffered.
                val decoded = decodeDatEntryOrThrow(zf, entry)
                onEntry(role, name, decoded.inputStream())
            } else {
                zf.getInputStream(entry).use { input -> onEntry(role, name, input) }
            }
        } catch (e: ZipException) {
            throw CorruptTransferException(
                "entry_inflate_failed",
                IllegalArgumentException(entry.name, e),
            )
        } catch (e: IOException) {
            throw CorruptTransferException(
                "entry_read_failed",
                IllegalArgumentException(entry.name, e),
            )
        }
    }

    /**
     * [DatCodec.decodeIfEncoded], with decode failures folded into the same "corrupt
     * transfer" story. [DatCodec.decode] signals a malformed archive via
     * `require`/`check` — [IllegalArgumentException] / [IllegalStateException] — not
     * an [IOException], so those are what a corrupt `.dat` payload actually raises.
     */
    private fun decodeDatEntryOrThrow(zf: ZipFile, entry: ZipEntry): ByteArray = try {
        DatCodec.decodeIfEncoded(zf.getInputStream(entry).use { it.readBytes() })
    } catch (e: IllegalArgumentException) {
        throw CorruptTransferException(
            "entry_datcodec_decode_failed",
            IllegalArgumentException(entry.name, e),
        )
    } catch (e: IllegalStateException) {
        throw CorruptTransferException(
            "entry_datcodec_decode_failed",
            IllegalArgumentException(entry.name, e),
        )
    }

    /** Writes one member: `.dat` encoded only under [encodeDatEntries] (see [isDatEntry]), else STORED or DEFLATED. */
    private fun putMember(
        zip: ZipOutputStream,
        member: Member,
        onBytes: (Long) -> Unit,
        encodeDatEntries: Boolean,
        storedCrcs: MutableMap<String, Long>,
    ) {
        val entryName = entryName(member.role, member.name)
        if (encodeDatEntries && isDatEntry(member.name)) {
            storedCrcs[entryName] =
                Zips.putStoredBytes(zip, entryName, DatCodec.encode(member.file.readBytes()), onBytes)
        } else if (shouldStore(member.name)) {
            storedCrcs[entryName] = Zips.putStored(zip, entryName, member.file, onBytes)
        } else {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            Zips.putFile(zip, entryName, member.file, onBytes)
        }
    }

    /** Ensure every member extracts byte-identical to its source before upload. */
    fun verifyRoundTrip(
        zip: File,
        members: List<Member>,
        encodeDatEntries: Boolean = false,
        storedCrcs: Map<String, Long> = emptyMap(),
    ) {
        ZipFile(zip).use { zf ->
            check(zf.size() == members.size) {
                "Session.zip entry count ${zf.size()} != payload ${members.size}"
            }
            members.forEach { member -> checkMember(zf, member, encodeDatEntries, storedCrcs) }
        }
    }

    private fun checkMember(
        zf: ZipFile,
        member: Member,
        encodeDatEntries: Boolean,
        storedCrcs: Map<String, Long>,
    ) {
        val name = entryName(member.role, member.name)
        val entry = zf.getEntry(name)
            ?: error("Session.zip missing entry $name after bundling")
        if (encodeDatEntries && isDatEntry(member.name)) {
            // An encoded entry never equals the source bytes, so decode it and compare
            // that: a codec bug is caught here, before promote, not on someone's restore.
            val decoded = DatCodec.decode(zf.getInputStream(entry).use { it.readBytes() })
            val got = Digests.toHex(Digests.sha256(decoded))
            val expect = Digests.sha256Hex(member.file)
            check(got == expect) {
                "Session.zip entry $name round-trip hash mismatch after DatCodec encode+decode"
            }
            return
        }
        if (entry.method == ZipEntry.STORED) {
            // STORED: compare the archive's CRC-32 with the source's (from putStored's pre-pass),
            // so the entry is never re-read. CRC suffices: the only risk is accidental corruption
            // of a file we just wrote, not untrusted input.
            val expectCrc = storedCrcs[name] ?: member.file.crc32()
            check(entry.crc == expectCrc) {
                "Session.zip entry $name round-trip CRC mismatch after bundling " +
                    "(archive=${entry.crc}, source=$expectCrc)"
            }
            return
        }
        val got = Digests.sha256HexStream(zf.getInputStream(entry))
        val expect = Digests.sha256Hex(member.file)
        check(got == expect) {
            "Session.zip entry $name round-trip hash mismatch after bundling"
        }
    }
}
