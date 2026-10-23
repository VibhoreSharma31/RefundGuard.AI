package com.refundguard.security;

/** The authenticated caller, taken from the Spring Security context - never from request bodies/headers. */
public record Actor(String username, Role role) {}
