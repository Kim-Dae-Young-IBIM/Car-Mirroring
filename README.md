# CarMirror

폰(Galaxy S26, Android 17) 화면 전체를 안드로이드 오토 차량 화면(기아 쏘렌토 MQ4 PE, ccNC)에 그대로 미러링하는 개인용 앱.
플레이스토어 배포 없이 내 폰에 직접 설치해서 쓴다.

현재 구현 범위: **1단계 — 화면 미러링**. (2단계 터치 조작은 아직 없음)

> ⚠️ 주행 중 영상 시청이나 폰 조작은 위험하고 도로교통법 위반이다. 정차 중에만 사용한다.

## 동작 구조

```
[폰 화면] --MediaProjection--> [VirtualDisplay (AUTO_MIRROR)] --직접 출력--> [차 화면 Surface]
                                       ^                                        ^
                              MirrorService (포그라운드)        MirrorCarAppService / MirrorScreen
                                        \_________ MirrorBridge (공유 상태) ______/
```

| 파일 | 역할 |
| --- | --- |
| `car/MirrorCarAppService.kt` | 안드로이드 오토 진입점. `NAVIGATION` 카테고리라 차 화면 Surface를 받을 수 있다 |
| `car/MirrorScreen.kt` | `NavigationTemplate` + 액션 스트립 "시작/정지". `SurfaceCallback`으로 Surface(가로·세로·dpi) 수신 |
| `MainActivity.kt` | 폰 쪽 화면. 화면 녹화 권한(`createScreenCaptureIntent`, 전체 화면 고정) 요청 |
| `MirrorService.kt` | `mediaProjection` 타입 포그라운드 서비스. VirtualDisplay를 차 Surface에 연결/분리, 화면 꺼짐 방지 |
| `MirrorBridge.kt` | 차 쪽과 폰 쪽이 공유하는 상태(차 Surface, 시작 여부, 권한 여부) |

구현 메모

- **레터박스**: VirtualDisplay를 차 Surface 크기로 만들고 `AUTO_MIRROR`로 폰 화면을 비추면, 시스템이 비율을 유지한 채 가운데 정렬하고 남는 부분을 검게 채운다. 폰을 가로로 돌리면 차 화면을 꽉 채운다.
- **Surface 교체**: Android 14부터 MediaProjection 하나로 VirtualDisplay를 한 번만 만들 수 있다. 그래서 차 Surface가 바뀌면 다시 만들지 않고 `resize()` + `setSurface()`로 갈아끼우고, Surface가 사라지면 `setSurface(null)`로 분리만 한다.
- **화면 꺼짐 방지**: 실제로 미러링 중일 때만 `SCREEN_DIM_WAKE_LOCK`을 잡는다(밝기는 어둡게, 캡처 결과는 그대로).
- **정리**: 차 연결 끊김 → 분리 + 시작 상태 해제, 권한 거부/토큰 만료 → 서비스 종료, 알림의 "종료" 또는 앱의 "미러링 종료" → 전부 해제. 모든 단계는 `CarMirror` 태그로 로그를 남긴다.

## 사전 준비 (폰에서 한 번만)

1. 안드로이드 오토 설정 맨 아래 **버전**을 10번 눌러 개발자 모드를 켠다.
2. 오른쪽 위 메뉴 → **개발자 설정** → **알 수 없는 출처**를 켠다.
3. 폰 설정 → 휴대전화 정보 → 소프트웨어 정보 → **빌드번호**를 7번 눌러 개발자 옵션을 켠다.
4. 개발자 옵션에서 **USB 디버깅**을 켠다.
5. PC에 Android Studio를 설치한다 (Android SDK + JDK 17 이상 포함).

## 빌드

PC에서 프로젝트 폴더를 열고:

```bash
# Android SDK 위치를 알려준다 (Android Studio로 한 번 열면 자동 생성됨)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties          # macOS: $HOME/Library/Android/sdk
                                                              # Windows: sdk.dir=C\:\\Users\\<이름>\\AppData\\Local\\Android\\Sdk
./gradlew assembleDebug                                       # Windows: gradlew.bat assembleDebug
```

결과물: `app/build/outputs/apk/debug/app-debug.apk`

- `compileSdk`/`targetSdk`는 36으로 되어 있다. Android 17 SDK(API 37)가 설치되어 있고 AGP가 지원하면 `app/build.gradle.kts`에서 올려도 된다.
- 사용 버전: Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20, Car App Library 1.7.0.

### PC 없이: GitHub Actions 빌드

어느 브랜치든 푸시하면 GitHub Actions(`.github/workflows/build-apk.yml`)가 APK를 만든다.

1. 폰 브라우저로 GitHub 저장소 → **Releases**에서 가장 최근 `CarMirror build N`을 연다.
2. `CarMirror-N.apk`를 받아 열고, "출처를 알 수 없는 앱 설치"를 브라우저에 허용해 설치한다.

- 앱은 저장소에 들어 있는 고정 디버그 키(`app/debug.keystore`)로 서명된다. 그래서 PC에서 빌드한 APK와 Actions에서 빌드한 APK를 서로 덮어 설치할 수 있다. 개인용 디버그 키라 공개돼도 상관없지만, 이 키로 서명한 앱을 남에게 배포하지는 않는다.
- Actions 탭의 Artifacts에도 같은 APK가 zip으로 올라간다.

## 설치

폰을 USB로 PC에 연결하고 (폰에 뜨는 "USB 디버깅 허용"을 승인):

```bash
adb devices                                                  # 폰이 device 로 보이는지 확인
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

또는 한 번에: `./gradlew installDebug`

### 안드로이드 오토 목록에 안 보일 때

최근 안드로이드 오토(17.x)는 "알 수 없는 소스"를 켜도 직접 설치한 Car App Library 앱을 숨길 수 있다.
안드로이드 오토는 앱을 누가 설치했는지 확인하는데, APK를 직접 설치하면 이 검사를 통과하지 못하기 때문이다.
해결하려면 APK를 **King Installer**(Shizuku 방식)로 설치해, 설치 기록이 플레이스토어(`com.android.vending`)로 남게 한다.
업데이트도 매번 같은 방법으로 설치해야 한다. 설치 기록은 아래 명령으로 확인할 수 있다:

```bash
adb shell dumpsys package com.personal.carmirror | grep -E "installerPackageName|initiatingPackageName"
# installerPackageName=com.android.vending 이면 정상
```

## 사용·테스트 순서

1. 폰에서 **CarMirror**를 열고 **화면 녹화 권한 허용 · 미러링 준비**를 누른다. 알림 권한도 허용하고, 녹화 범위는 **전체 화면**을 고른다.
2. 차에 연결해 안드로이드 오토 앱 목록에서 **CarMirror**를 연다. 안 보이면 안드로이드 오토 설정 → 런처 맞춤설정에서 표시를 켠다.
3. 차 화면 오른쪽 위의 **시작**을 누른다 → 폰 화면이 차에 뜬다. **정지**를 누르면 멈춘다.
4. 완전히 끝내려면 폰 알림의 **종료** 또는 앱의 **미러링 종료**를 누른다.
5. 문제가 생기면 로그를 모아서 Claude Code에 붙여 넣는다:

```bash
adb logcat -s CarMirror:* CarApp:* AndroidRuntime:E
```

- [ ] 1단계: 미러링 화면이 차에 뜬다
- [ ] 2단계: 차 화면 터치로 폰이 조작된다

## 한계와 주의사항

- 내비 앱 화면을 미러링에 쓰는 건 구글이 의도한 용도가 아니라 안드로이드 오토 업데이트로 언제든 막힐 수 있다.
- 화면 녹화 권한은 미러링을 시작할 때마다(앱/서비스를 새로 시작할 때마다) 다시 허용해야 한다. 한 번 허용하면 서비스를 종료하기 전까지는 차를 껐다 켜도 폰에서 다시 허용할 필요 없다.
- 폰이 잠기면 잠금화면이 그대로 미러링되며, 일부 Android 버전에서는 잠금 시 화면 공유가 자동으로 끊길 수 있다(이 경우 폰에서 다시 허용).
- 미러링 중에는 폰 화면이 켜져 있어야 해서 배터리 소모와 발열이 크다.
- 영상 앱(넷플릭스 등)은 저작권 보호로 검은 화면만 나올 수 있다.
- 차 화면 위쪽에 안내 카드와 "시작/정지" 버튼이 겹쳐 보인다(안내 카드는 미러링 중에는 숨김).
- 무선 안드로이드 오토는 화면을 압축해 보내서 유선보다 끊기거나 늦을 수 있다. 화질·지연이 거슬리면 USB 유선으로 비교해 본다.
