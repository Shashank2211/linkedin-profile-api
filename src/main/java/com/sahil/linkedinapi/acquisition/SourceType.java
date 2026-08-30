package com.sahil.linkedinapi.acquisition;

/**
 * Where a response came from. Echoed to the caller as {@code meta.source} — provenance
 * is part of the contract, because "we got this from the public page" and "we got this
 * from the authenticated API" are answers of very different completeness.
 */
public enum SourceType {

    /** Authenticated internal REST-li API. Richest. */
    VOYAGER,

    /** Logged-out public profile page, read via JSON-LD then DOM. Thin but resilient. */
    PUBLIC_HTML,

    /** Served from cache without touching LinkedIn at all. */
    CACHE
}
