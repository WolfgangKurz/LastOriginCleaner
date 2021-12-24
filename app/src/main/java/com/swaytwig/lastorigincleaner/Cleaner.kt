package com.swaytwig.lastorigincleaner

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Switch
import androidx.annotation.RequiresApi
import java.io.File
import java.lang.Exception

class MatchedUriFileList(var uri: Uri, var list: List<DocumentFileMeta>)

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
fun findMatchedUriFileList(context: Context, path: String, uris: List<Uri>): MatchedUriFileList? {
    for (uri in uris) {
        try {
            val list = getSAFFileList(context, uri, path)
            if (list.isNotEmpty()) return MatchedUriFileList(uri, list)
        } catch (e: Exception) {
            continue
        }
    }
    return null
}

fun sizeReadable(size: Long): String {
    var dSize: Float = size.toFloat()
    var step = 0
    while (dSize >= 1000) {
        dSize /= 1024
        step++
    }

    val s = dSize.toString()
    return when {
        step == 1 -> s + "KBs"
        step == 2 -> s + "MBs"
        step == 3 -> s + "GBs"
        step == 4 -> s + "PBs"
        else -> size.toString() + "bytes"
    }
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
fun getSAFFileList(context: Context, uri: Uri, path: String): List<DocumentFileMeta> {
    val u = getTreeDocumentUriByPath(path, uri) ?: return listOf()
    return SAFListChildren(context, u)
}

fun Cleaner(
    context: Context,
    storages: List<String>,
    switches: HashMap<Switch, String>,
    Log: (String) -> Unit
) {
    fun findMatchedStorageFileList(path: String): Array<File>? {
        for (storage in storages) {
            val target = when {
                storage == "emulated" -> "emulated/0"
                else -> storage
            }
            val f = File("/storage/$target/$path")
            if (!f.exists()) continue

            return f.listFiles()
        }
        return null
    }

    var totalSize: Long = 0

    for ((switch, path) in switches) {
        val name = switch.text.toString()
        if (!switch.isChecked) continue // 스위치 체크 안된거 넘어가기
        var removedSize: Long = 0

        val fullPath = "$path/files/UnityCache/Shared/"
        val dirs = findMatchedStorageFileList(fullPath)
        if (dirs == null) {
            Log(String.format(context.getString(R.string.CLEAN_TARGET_NOT_EXISTS), name))
            continue
        }

        for (target in dirs) { // 대상 디렉터리들
            if (!target.isDirectory) continue

            if (isOldDirectory(target)) { // 구버전 캐시 파일
                val dirname =
                    target.canonicalPath.substring(target.canonicalPath.indexOf(fullPath) + fullPath.length)
                val size = target.walkTopDown().filter { it.isFile }.map { it.length() }.sum()

                removedSize += size
                target.deleteRecursively()
                Log(
                    String.format(
                        context.getString(R.string.CLEAN_DIRECTORY_REMOVED),
                        dirname,
                        sizeReadable(size)
                    )
                )
                continue
            }

            val list = target.listFiles() ?: continue // 버전별 디렉터리들, 실패했으면 패스
            if (list.size <= 1) continue // 하나만 있으면 패스

            val latest = list.maxBy { r -> r.lastModified() }!!.lastModified()
            for (tdir in list) {
                val dirname =
                    tdir.canonicalPath.substring(tdir.canonicalPath.indexOf(fullPath) + fullPath.length)
                val size = tdir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()

                if (tdir.lastModified() == latest) continue
                removedSize += size
                tdir.deleteRecursively()
                Log(
                    String.format(
                        context.getString(R.string.CLEAN_DIRECTORY_REMOVED),
                        dirname,
                        sizeReadable(size)
                    )
                )
            }
        }

        Log(
            String.format(
                context.getString(R.string.CLEAN_TARGET_CLEANED),
                name,
                sizeReadable(removedSize)
            )
        )
        totalSize += removedSize
    }
    Log(String.format(context.getString(R.string.CLEAN_ALL_CLEANED), sizeReadable(totalSize)))
}

fun isOldDirectory(dir: File): Boolean {
    val name = dir.name
    return (name.length == 40) and Regex("^[0-9a-f]$").matches(name)
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
fun CleanerSAF(
    context: Context,
    uris: MutableList<Uri>,
    switches: HashMap<Switch, String>,
    Log: (String) -> Unit
) {
    var totalSize: Long = 0

    for ((switch, path) in switches) {
        val name = switch.text.toString()
        if (!switch.isChecked) continue // 스위치 체크 안된거 넘어가기
        var removedSize: Long = 0

        val fullPath = "$path/files/UnityCache/Shared/"
        val dirs = findMatchedUriFileList(context, fullPath, uris)
        if (dirs == null) { // 모든 Granted Uri에 없었던 경우
            Log(String.format(context.getString(R.string.CLEAN_TARGET_NOT_EXISTS), name))
            continue
        }

        for (target in dirs.list) { // 대상 디렉터리들
            if (!target.isDirectory) continue

            if (isOldDirectory(target)) { // 구버전 캐시 파일
                val dirname = target.name
                val size = SAFDirectorySize(context, target)

                removedSize += size
                SAFRemove(context, target)
                Log(
                    String.format(
                        context.getString(R.string.CLEAN_DIRECTORY_REMOVED),
                        dirname,
                        sizeReadable(size)
                    )
                )
                continue
            }

            val list =
                getSAFFileList(context, dirs.uri, fullPath + target.name) // 버전별 디렉터리들, 실패했으면 패스
            if (list.size <= 1) continue // 하나만 있으면 패스

            val latest = list.maxBy { r -> r.timestamp }!!.timestamp
            for (tdir in list) {
                val dirname = target.name + "/" + tdir.name
                val size = SAFDirectorySize(context, tdir)

                if (tdir.timestamp == latest) continue
                removedSize += size

                SAFRemove(context, tdir)
                Log(
                    String.format(
                        context.getString(R.string.CLEAN_DIRECTORY_REMOVED),
                        dirname,
                        sizeReadable(size)
                    )
                )
            }
        }

        Log(
            String.format(
                context.getString(R.string.CLEAN_TARGET_CLEANED),
                name,
                sizeReadable(removedSize)
            )
        )
        totalSize += removedSize
    }
    Log(String.format(context.getString(R.string.CLEAN_ALL_CLEANED), sizeReadable(totalSize)))
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
fun isOldDirectory(dir: DocumentFileMeta): Boolean {
    val name = dir.name
    if (name == null) return false
    return (name.length == 24) and Regex("^[0-9a-f]$").matches(name)
}
