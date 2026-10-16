package dev.agentmeter

import java.io.File
import java.nio.file.Path

/** Shows a path under the home directory as "~/..." (with the OS separator) so the user name stays out of the UI. */
fun tildePath(path: Path, home: Path): String {
    val p = path.toAbsolutePath().normalize()
    val h = home.toAbsolutePath().normalize()
    if (!p.startsWith(h)) return p.toString()
    val rest = h.relativize(p).toString()
    return if (rest.isEmpty()) "~" else "~" + File.separator + rest
}
