package com.socialdrive.app.data.fake

import com.socialdrive.app.domain.DriveMoment
import com.socialdrive.app.domain.DriveSummary
import com.socialdrive.app.domain.NavigationPlace
import com.socialdrive.app.domain.NavigationRequest
import com.socialdrive.app.domain.RoutePoint
import com.socialdrive.app.domain.RouteSource

interface DriveRepository {
    fun drives(): List<DriveSummary>
    fun drive(id: String): DriveSummary?
}

class FakeDriveRepository : DriveRepository {
    private val coastRoute = listOf(
        RoutePoint(.06f, .72f), RoutePoint(.18f, .58f), RoutePoint(.31f, .63f),
        RoutePoint(.45f, .39f), RoutePoint(.62f, .45f), RoutePoint(.78f, .24f), RoutePoint(.94f, .32f),
    )
    private val mountainRoute = listOf(
        RoutePoint(.05f, .7f), RoutePoint(.17f, .42f), RoutePoint(.32f, .54f),
        RoutePoint(.48f, .25f), RoutePoint(.64f, .37f), RoutePoint(.79f, .18f), RoutePoint(.95f, .41f),
    )
    private val cityRoute = listOf(
        RoutePoint(.05f, .68f), RoutePoint(.2f, .68f), RoutePoint(.2f, .43f),
        RoutePoint(.44f, .43f), RoutePoint(.44f, .25f), RoutePoint(.7f, .25f), RoutePoint(.7f, .55f), RoutePoint(.95f, .55f),
    )

    private val items = listOf(
        DriveSummary(
            id = "demo-coast",
            date = "10월 28일 · 데모 경로",
            title = "빛나는 해안도로의 저녁",
            summary = "바다를 따라 천천히 이어지는 네 번의 좋은 순간",
            distance = "128.4 km",
            duration = "2시간 36분",
            highlights = 4,
            route = coastRoute,
            moments = listOf(
                DriveMoment("m1", "17:24", "노을이 시작된 순간", "따뜻한 빛이 길 위로 번졌어요."),
                DriveMoment("m2", "18:03", "등대가 보이는 길", "잠시 머물고 싶었던 풍경이에요."),
                DriveMoment("m3", "18:41", "바다를 닮은 쉼", "고요한 파도와 나란히 달렸어요."),
                DriveMoment("m4", "19:17", "야경이 빛나는 길", "오늘의 마지막 하이라이트예요."),
            ),
            routeSource = RouteSource.DemoPreset,
            navigationRequest = NavigationRequest(
                requestId = "demo-public-destination-seoul",
                destination = NavigationPlace(
                    name = "공개 데모 목적지",
                    latitude = 37.5665,
                    longitude = 126.9780,
                ),
            ),
        ),
        DriveSummary(
            id = "demo-mountain",
            date = "10월 22일 · 데모 경로",
            title = "가을, 단풍이 머문 길",
            summary = "붉게 물든 산길의 차분한 하루",
            distance = "86.1 km",
            duration = "1시간 52분",
            highlights = 3,
            route = mountainRoute,
            moments = emptyList(),
            routeSource = RouteSource.DemoPreset,
        ),
        DriveSummary(
            id = "demo-city",
            date = "10월 15일 · 데모 경로",
            title = "도심 속 특별한 저녁",
            summary = "익숙한 길도 새롭게 빛나는 순간",
            distance = "32.8 km",
            duration = "58분",
            highlights = 2,
            route = cityRoute,
            moments = emptyList(),
            routeSource = RouteSource.DemoPreset,
        ),
    )

    override fun drives(): List<DriveSummary> = items
    override fun drive(id: String): DriveSummary? = items.firstOrNull { it.id == id }
}
