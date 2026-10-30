package com.roboff.lan.cloud

import java.io.IOException

/** A user-safe setup error that never includes response bodies, tokens, passwords, or codes. */
class CloudException @JvmOverloads constructor(
    val code: Int,
    message: String,
    val retryAfterSeconds: Int = 0,
) : IOException(message)
