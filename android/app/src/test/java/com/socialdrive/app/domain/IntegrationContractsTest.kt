package com.socialdrive.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IntegrationContractsTest {
    private val destination = NavigationPlace("검증용 목적지", 0.0, 0.0)

    @Test
    fun `accepts finite named coordinate without storing an address`() {
        val request = NavigationRequest("request-1", destination)

        assertEquals(NavigationValidation.Valid, NavigationRequestValidator.validate(request))
    }

    @Test
    fun `rejects non finite and out of range coordinates`() {
        val invalid = listOf(
            destination.copy(latitude = Double.NaN),
            destination.copy(latitude = 90.1),
            destination.copy(longitude = Double.POSITIVE_INFINITY),
            destination.copy(longitude = -180.1),
        )

        invalid.forEach { place ->
            assertIs<NavigationValidation.Invalid>(
                NavigationRequestValidator.validate(NavigationRequest("request-1", place)),
            )
        }
    }

    @Test
    fun `rejects blank names and more than one waypoint`() {
        assertIs<NavigationValidation.Invalid>(
            NavigationRequestValidator.validate(NavigationRequest("request-1", destination.copy(name = ""))),
        )
        assertIs<NavigationValidation.Invalid>(
            NavigationRequestValidator.validate(
                NavigationRequest("request-1", destination, listOf(destination, destination)),
            ),
        )
    }
}
