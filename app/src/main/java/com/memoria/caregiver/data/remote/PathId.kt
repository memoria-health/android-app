package com.memoria.caregiver.data.remote

import java.util.Locale

/**
 * An identifier on its way into a URL path.
 *
 * Every Swift call site builds paths from `id.uuidString.lowercased()`, because
 * Foundation renders a `UUID` in upper case and the backend, the web client and
 * the audit logs all expect the lower-case form. Ids here are `String`s decoded
 * straight off the wire and already lower case, so in practice this changes
 * nothing — it exists so that an id arriving from somewhere else (a deep link,
 * a pasted value, a test fixture) cannot produce a path iOS would never send.
 *
 * `Locale.ROOT` is stated explicitly, but it is documentation rather than a
 * fix: Kotlin's no-argument `lowercase()` already lowers with `Locale.ROOT`
 * internally — that is the whole reason it replaced `toLowerCase()`, which did
 * use the device locale and did turn `I` into a dotless `ı` on a Turkish
 * device. Naming the locale here means nobody has to remember which of the two
 * spellings was the safe one.
 *
 * Five API groups were written in parallel and three of them grew their own
 * spelling of this one line. One definition, so the rule cannot drift.
 */
internal fun pathId(id: String): String = id.lowercase(Locale.ROOT)
