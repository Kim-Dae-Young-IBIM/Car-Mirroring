# CarMirror

폰(Galaxy S26, Android 17) 화면 전체를 안드로이드 오토 차량 화면(기아 쏘렌토 MQ4 PE, ccNC)에 그대로 미러링하는 개인용 앱.
플레이스토어 배포 없이 내 폰에 직접 설치해서 쓴다.

현재 구현 범위: **1단계 — 화면 미러링** (실차 확인 완료), **2단계 — 차 화면 터치로 폰 조작** (실차 확인 전).

> ⚠️ 주행 중 영상 시청이나 폰 조작은 위험하고 도로교통법 위반이다. 정차 중에만 사용한다.

## 동작 구조

```
[폰 화면] --MediaProjection--> [VirtualDisplay (AUTO_MIRROR)] --> [MirrorRenderer (OpenGL)] --> [차 화면 Surface]
                                       ^                            보이는 영역에 맞춰 그림          |
                              MirrorService (포그라운드) <---- 탭/스크롤/플링 (SurfaceCallback) -----+
                                       |                                                          
                              TouchInjectorService (접근성) --> 폰에 탭·스와이프 입력
                                        \_________ MirrorBridge (공유 상태) ______/
```

| 파일 | 역할 |
| --- | --- |
| `car/MirrorCarAppService.kt` | 안드로이드 오토 진입점. `NAVIGATION` 카테고리라 차 화면 Surface를 받을 수 있다 |
| `car/MirrorScreen.kt` | `NavigationTemplate` + 액션 스트립 "시작/정지" + 이동(PAN) 버튼. `SurfaceCallback`으로 Surface, 보이는 영역, 터치 입력 수신 |
| `MainActivity.kt` | 폰 쪽 화면. 화면 녹화 권한(`createScreenCaptureIntent`, 전체 화면 고정) 요청, 설정 상태 표시 |
| `MirrorService.kt` | `mediaProjection` 타입 포그라운드 서비스. VirtualDisplay·렌더러 관리, 차 터치 → 폰 좌표 변환, 화면 꺼짐 방지 |
| `MirrorRenderer.kt` | 폰 화면 프레임(SurfaceTexture)을 차 Surface의 보이는 영역 안에 비율 유지해 그림 (EGL/GLES2, 전용 스레드) |
| `TouchInjectorService.kt` | 접근성 서비스. `dispatchGesture`로 폰에 탭·스와이프 입력 |
| `MirrorBridge.kt` | 차 쪽과 폰 쪽이 공유하는 상태(차 Surface, 보이는 영역, 시작 여부, 권한 여부, 입력 전달) |

구현 메모

- **보이는 영역에 맞춤**: 넓은 화면에서 안드로이드 오토는 오른쪽에 미디어 카드를 띄우지만 앱 Surface는 그 뒤까지 포함한다. 그래서 VirtualDisplay를 차 Surface에 직접 붙이지 않고, 렌더러가 고정 영역(stable area, 없으면 visible area) 안에 비율을 유지해 최대로 그린다. 폰을 회전하면 캡처 크기(긴 변 최대 1920)를 폰 비율에 맞춰 `resize()`한다.
- **터치**: 탭은 그린 위치(`contentRect`) 기준으로 폰 좌표로 바꿔 그대로 입력한다. 스크롤은 잘게 오는 이벤트를 80ms 모아 폰 화면 가운데에서 한 번의 드래그로, 플링은 손가락 속도 방향으로 짧고 빠른 스와이프로 입력한다. 안드로이드 오토는 멀티터치(핀치)와 길게 누르기는 앱에 보내지 않는다. 드래그는 이동(PAN) 버튼을 눌러 이동 모드에 들어가야 전달될 수 있다.
- **토큰 재사용 제한**: Android 14부터 MediaProjection 하나로 VirtualDisplay를 한 번만, 권한을 받은 지 5분 안에만 만들 수 있다. 그래서 권한을 받자마자 출력 없는 VirtualDisplay를 만들고, 미러링할 때만 렌더러 입력 Surface를 끼운다.
- **화면 꺼짐 방지**: 차와 연결된 동안 `SCREEN_DIM_WAKE_LOCK`을 잡는다(밝기는 어둡게, 캡처 결과는 그대로).
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
안드로이드 오토는 앱을 누가 설치했는지 확인하고, 설치 기록이 Play 스토어(`com.android.vending`)여야 통과한다.

CarMirror 앱 아래쪽 **안드로이드 오토 표시용 설치**에서 바로 해결할 수 있다 (Shizuku·King Installer 불필요):

1. 현재 설치 출처가 표시된다. ⚠️면 **설치 출처 고치기**를 누른다.
2. 처음 한 번은 CarMirror에 "이 출처 허용"을 켜야 한다 (설정 화면이 자동으로 열림). 켠 뒤 다시 누른다.
3. 시스템 설치 화면에서 **업데이트**를 누르면 앱이 닫히고, Play 스토어가 설치한 것으로 기록된 채 다시 설치된다.
4. CarMirror를 다시 열어 ✅인지 확인 → 안드로이드 오토 강제 중지 → 런처 맞춤설정 확인.
5. 앞으로 업데이트는 앱의 **최신 빌드 받아서 설치**로 한다. 브라우저로 받아 설치하면 기록이 다시 바뀐다.

원리: 시스템 설치 프로그램을 띄우면서 `EXTRA_INSTALLER_PACKAGE_NAME=com.android.vending`을 넘긴다.
새 버전 설치 프로그램(AOSP `v2/InstallRepository`)은 이 값을 받아들이지만, 예전 설치 프로그램(`InstallStart`)은 무시한다.
그래서 기기에 따라 안 될 수 있고, 결과는 앱에 표시되는 설치 출처로 바로 확인할 수 있다.
안 되면 King Installer(Shizuku 방식)로 설치하거나 Play 내부 테스트 트랙을 쓴다. PC에서는 아래 명령으로 확인할 수 있다:

```bash
adb shell dumpsys package com.personal.carmirror | grep -E "installerPackageName|initiatingPackageName"
```

## 사용·테스트 순서

1. 폰에서 **CarMirror**를 열고 **화면 녹화 권한 허용 · 미러링 준비**를 누른다. 알림 권한도 허용하고, 녹화 범위는 **전체 화면**을 고른다.
2. 차에 연결해 안드로이드 오토 앱 목록에서 **CarMirror**를 연다. 안 보이면 안드로이드 오토 설정 → 런처 맞춤설정에서 표시를 켠다.
3. 차 화면 오른쪽 위의 **시작**을 누른다 → 폰 화면이 차에 뜬다. **정지**를 누르면 멈춘다.
4. 완전히 끝내려면 폰 알림의 **종료** 또는 앱의 **미러링 종료**를 누른다.
5. 차 화면 터치(2단계)는 폰에서 한 번 설정한다: 앱의 **접근성 설정 열기** → 설치된 앱 → **CarMirror 차 화면 터치** 켜기. 회색으로 막혀 있으면 **앱 정보 열기** → 오른쪽 위 ⋮ → **제한된 설정 허용**을 먼저 누른다. 드래그가 안 먹으면 차 화면의 이동(십자 화살표) 버튼을 누른 뒤 드래그한다.
6. 문제가 생기면 로그를 모아서 Claude Code에 붙여 넣는다:

```bash
adb logcat -s CarMirror:* CarApp:* AndroidRuntime:E
```

- [x] 1단계: 미러링 화면이 차에 뜬다 (쏘렌토 ccNC, 유선, 회전 확인)
- [ ] 2단계: 차 화면 터치로 폰이 조작된다

## 한계와 주의사항

- 내비 앱 화면을 미러링에 쓰는 건 구글이 의도한 용도가 아니라 안드로이드 오토 업데이트로 언제든 막힐 수 있다.
- **폰이 잠기면 미러링이 끊긴다 (Android 15+).** 화면 녹화 중 잠금화면이 뜨면 시스템이 녹화를 멈춘다. 그래서:
  - 권한은 **차에 연결한 뒤** 폰 잠금을 풀고 허용한다. 집에서 허용해 두면 이동 중 폰이 잠기면서 끊긴다.
  - 차와 연결된 동안은 앱이 화면을 켜 둬서 자동 잠금은 막지만, 전원 버튼으로 잠그면 끊긴다.
  - 잠겨도 유지하려면 폰 개발자 옵션 → **화면 공유 보호 사용 중지**를 켠다 (앱 화면에 현재 상태가 표시됨). 대신 화면 공유 중 알림·비밀번호 가림 보호가 꺼진다.
- 화면 녹화 권한 토큰은 받은 지 5분 안에 써야 한다 (Android 14+). 그래서 권한을 받자마자 가상 디스플레이를 만들어 두고, 차 화면은 나중에 연결한다.
- 통화 중에 권한을 허용했다면 통화가 끝날 때 녹화가 멈춘다 (Android 16+).
- 미러링 중에는 폰 화면이 켜져 있어야 해서 배터리 소모와 발열이 크다.
- 영상 앱(넷플릭스 등)은 저작권 보호로 검은 화면만 나올 수 있다.
- 차 화면 위쪽에 안내 카드와 "시작/정지" 버튼이 겹쳐 보인다(안내 카드는 미러링 중에는 숨김).
- 무선 안드로이드 오토는 화면을 압축해 보내서 유선보다 끊기거나 늦을 수 있다. 화질·지연이 거슬리면 USB 유선으로 비교해 본다.
