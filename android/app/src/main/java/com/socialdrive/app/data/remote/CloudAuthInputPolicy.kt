package com.socialdrive.app.data.remote

object CloudAuthInputPolicy {
    const val MAX_USERNAME_CHARS = 256
    const val MAX_PASSWORD_CHARS = 256
    const val MAX_CHALLENGE_CHARS = 2_048

    fun limitUsername(value: String): String = value.take(MAX_USERNAME_CHARS)

    fun limitPassword(value: String): String = value.take(MAX_PASSWORD_CHARS)

    fun limitChallenge(value: String): String = value.take(MAX_CHALLENGE_CHARS)

    fun acceptsCredentials(username: String, password: CharArray): Boolean =
        username.isNotBlank() && username.length <= MAX_USERNAME_CHARS &&
            password.isNotEmpty() && password.size <= MAX_PASSWORD_CHARS

    fun acceptsChallenge(response: CharArray): Boolean =
        response.isNotEmpty() && response.size <= MAX_CHALLENGE_CHARS
}
