package com.memoria.caregiver.legal

/**
 * Single source of truth for the legal-document acceptance gate.
 * Port of `Features/Legal/LegalConstants.swift`.
 *
 * Keep [currentTosVersion] in sync with the web app's `CURRENT_TOS_VERSION` and
 * the backend's `KNOWN_TOS_VERSIONS` allowlist — the server rejects any other
 * value. [documents] are the bundled PDFs in `assets/legal/`; swap those files
 * for the finalized docs when ready (QUESTIONS.md Q4, REQ-10 — replace on iOS
 * and Android together, and do not author replacement copy here).
 *
 * ## Why this is its own top-level package rather than a constant in `ui.auth`
 *
 * Two different layers need it and neither should own it. The version string is
 * sent to the server by the sign-up call, and the document list is rendered by
 * the consent gate. It sits beside `analytics` for the same reason that package
 * does: it is shared vocabulary, not screen code and not transport code.
 */
object LegalConstants {

    /**
     * The version of the Terms and Privacy Notice a caregiver is agreeing to.
     *
     * **This is a consent record, not a display string.** It is stored against
     * the caregiver row and is the only thing that says *which* document they
     * agreed to, so there is exactly one definition of it in the app and no
     * literal copy of it anywhere else. Changing it means the documents changed,
     * and every existing caregiver's stored version becomes stale.
     */
    const val currentTosVersion = "2026-07-26-placeholder"

    /**
     * One reviewable legal document. iOS: `LegalConstants.Doc`.
     *
     * @param resource asset name under `assets/legal/`, without the `.pdf`
     *   extension.
     */
    data class Doc(
        val id: String,
        val title: String,
        val resource: String,
    )

    /** Both documents, in the order their tabs appear in the gate. */
    val documents: List<Doc> = listOf(
        Doc(id = "terms", title = "Terms of Service", resource = "terms"),
        Doc(id = "privacy", title = "Privacy Notice", resource = "privacy"),
    )
}
