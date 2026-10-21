package com.personal.carmirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 안드로이드 오토는 직접 설치한 Car App Library 앱을 런처에서 숨긴다.
 * 설치 기록이 "Play 스토어가 설치함"(installer=com.android.vending,
 * initiator=시스템 패키지 설치 프로그램)이면 통과하므로, CarMirror가 스스로
 * 시스템 설치 프로그램을 띄워 그 기록으로 자기 자신을 다시 설치한다.
 *
 * 시스템 설치 프로그램이 EXTRA_INSTALLER_PACKAGE_NAME을 받아들이는지는 기기/버전마다 다르다.
 * 그래서 설치 후 [readSource]로 실제 기록을 확인해 화면에 보여준다.
 */
object SelfInstaller {

    const val PLAY_STORE = "com.android.vending"
    private const val RELEASES_API =
        "https://api.github.com/repos/Kim-Dae-Young-IBIM/Car-Mirroring/releases?per_page=1"

    data class Source(val installer: String?, val initiator: String?) {
        val isPlay: Boolean get() = installer == PLAY_STORE
    }

    fun readSource(context: Context): Source {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = pm.getInstallSourceInfo(context.packageName)
            Source(info.installingPackageName, info.initiatingPackageName)
        } else {
            @Suppress("DEPRECATION")
            Source(pm.getInstallerPackageName(context.packageName), null)
        }
    }

    /** "출처를 알 수 없는 앱 설치" 권한이 없으면 설정 화면을 열고 false. */
    fun ensureInstallPermission(activity: Activity): Boolean {
        if (activity.packageManager.canRequestPackageInstalls()) return true
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${activity.packageName}"))
        )
        return false
    }

    /** 지금 설치된 APK를 그대로 복사한다 (버전은 같고 설치 기록만 바뀐다). */
    fun copyInstalledApk(context: Context): File {
        val out = apkFile(context)
        File(context.applicationInfo.sourceDir).copyTo(out, overwrite = true)
        return out
    }

    /** GitHub Releases의 가장 최근 빌드 APK를 받는다. 네트워크를 쓰므로 백그라운드 스레드에서 호출. */
    fun downloadLatestApk(context: Context): Pair<String, File> {
        val releases = JSONArray(httpGet(RELEASES_API).bufferedReader().use { it.readText() })
        require(releases.length() > 0) { "릴리스가 없습니다" }
        val release = releases.getJSONObject(0)
        val assets = release.getJSONArray("assets")
        val apkUrl = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }
            ?.getString("browser_download_url")
            ?: error("릴리스에 APK가 없습니다")
        val out = apkFile(context)
        httpGet(apkUrl).use { input -> out.outputStream().use { input.copyTo(it) } }
        Log.i(MirrorService.TAG, "최신 빌드 다운로드 완료: ${release.getString("name")}")
        return release.getString("name") to out
    }

    /** 시스템 설치 프로그램에 "Play 스토어가 설치함"으로 기록해 달라고 요청한다. */
    @Suppress("DEPRECATION")
    fun launchInstall(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, PLAY_STORE)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        Log.i(MirrorService.TAG, "설치 출처를 Play 스토어로 지정해 재설치 요청")
        activity.startActivity(intent)
    }

    private fun apkFile(context: Context): File =
        File(context.cacheDir, "apk").apply { mkdirs() }.resolve("CarMirror.apk")

    private fun httpGet(url: String) =
        (URL(url).openConnection() as HttpURLConnection).run {
            setRequestProperty("User-Agent", "CarMirror")
            setRequestProperty("Accept", "application/vnd.github+json, */*")
            connectTimeout = 15_000
            readTimeout = 60_000
            if (responseCode !in 200..299) error("HTTP $responseCode: $url")
            inputStream
        }
}
