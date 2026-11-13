package com.sempermechanics.semper.util

import java.io.File

/**
 * Writes [dest] the [AtomicFiles] way: [write] fills [tmp], and only once it
 * returns is [tmp] promoted onto [dest] ([AtomicFiles.promote]). A reader never
 * sees a half-written [dest].
 *
 * Unless the promote completes, the `finally` deletes [tmp] and the throwable
 * (cancellation included) propagates unchanged, so no abandoned sidecar is
 * mistaken for a finished one by the next run.
 *
 * [write] is `crossinline`: inlined, a non-local `return` would skip the
 * `finally` and leave the sidecar. Stop early with `return@writeVia value`
 * (which promotes what was written) or throw.
 *
 * No `fd.sync()` here: a caller that needs the bytes on disk before the rename
 * (`SessionStore`'s index) syncs inside [write].
 *
 * [tmp] defaults to [AtomicFiles.partOf]; a caller whose sidecar has another
 * established name (`index.json.tmp`, `Session.zip.tmp`, `<part>.tmp`) passes
 * it, because cleanup code elsewhere deletes the sidecar by that name.
 * [clearDest] deletes [dest] just before the promote; leave it off where [dest]
 * must never be missing (see [AtomicFiles.promote]).
 *
 * Not for resumable downloads: those keep their `.part` across attempts on
 * purpose ([com.sempermechanics.semper.data.net.drive.DriveTransfer]).
 *
 * @return what [write] returned (a digest, a count, …).
 */
inline fun <T> AtomicFiles.writeVia(
    dest: File,
    tmp: File = partOf(dest),
    clearDest: Boolean = false,
    crossinline write: (tmp: File) -> T,
): T {
    var promoted = false
    try {
        val result = write(tmp)
        if (clearDest) dest.delete()
        promote(tmp, dest)
        promoted = true
        return result
    } finally {
        if (!promoted) tmp.delete()
    }
}
