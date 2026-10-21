package com.personal.carmirror.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** 안드로이드 오토가 붙는 진입점. NAVIGATION 카테고리로 선언되어 차 화면 Surface를 받는다. */
class MirrorCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // 디버그 빌드(직접 설치)는 모든 호스트 허용. 안드로이드 오토 "알 수 없는 출처"와 함께 사용
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = MirrorSession()
}

class MirrorSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = MirrorScreen(carContext)
}
