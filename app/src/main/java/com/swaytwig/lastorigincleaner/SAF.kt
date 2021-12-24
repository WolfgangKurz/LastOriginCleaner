package com.swaytwig.lastorigincleaner

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings.System.canWrite
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile

val COLUMNS = arrayOf(
    "document_id",
    "_display_name",
    "_size",
    "last_modified",
    "mime_type",
    "flags"
)

val primaryTreeURi = Uri.parse(
    String.format("content://com.android.externalstorage.documents/tree/%s", Uri.encode("primary:"))
)

fun getSAFTreeUri(uri: Uri = primaryTreeURi): Uri? {
    var b = uri.toString()
    b = b.substring(b.indexOf("/tree/") + 6)
    b = b.substring(0, b.indexOf("%3A") + 3)
    return Uri.parse(
        String.format(
            "content://com.android.externalstorage.documents/tree/%s",
            b
        )
    ) // Uri.encode("primary:")))
}

fun isRootUri(uri: Uri): Boolean {
    return uri.toString().endsWith(Uri.encode(":"))
}

fun isStringEmpty(paramCharSequence: CharSequence?): Boolean {
    return paramCharSequence == null || paramCharSequence.length == 0
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
fun getTreeDocumentUriByPath(paramString: String, uri: Uri): Uri? {
    val treeUri = getSAFTreeUri(uri)
    if (treeUri != null) {
        // getDocumentUriFromTreeUri(treeUri)
        val sb = StringBuilder()
        sb.append(DocumentsContract.getTreeDocumentId(treeUri))
        sb.append(paramString)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, sb.toString())
    }
    return null
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
fun SAFListChildren(context: Context, paramUri: Uri): MutableList<DocumentFileMeta> {
    val contentResolver = context.contentResolver
    android.util.Log.d(
        "isDocumentUri",
        DocumentsContract.isDocumentUri(context, paramUri).toString()
    )
    android.util.Log.d("paramUri", paramUri.toString())

    val uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            paramUri,
            DocumentsContract.getDocumentId(paramUri)
        )
    val list = mutableListOf<DocumentFileMeta>()

    try {
        val cursor = contentResolver.query(uri, COLUMNS, null, null, null)
        if (cursor != null) {
            while (true) {
                if (cursor.moveToNext()) {
                    if (isStringEmpty(cursor.getString(1))) {
                        Log.w(
                            "SAF",
                            arrayOf<Any>(
                                "File name for uri ",
                                cursor.getString(0),
                                " is empty!"
                            ).joinToString("")
                        )
                        continue
                    }
                    list.add(fromDocumentsContractChild(paramUri, cursor))
                    continue
                }
                cursor.close()
                return list
            }
        }
    } catch (e: Exception) {
    }
    return list
}

@RequiresApi(Build.VERSION_CODES.KITKAT)
fun SAFRemove(context: Context, document: DocumentFileMeta) {
    DocumentsContract.deleteDocument(context.contentResolver, document.uri)
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
fun SAFDirectorySize(context: Context, document: DocumentFileMeta): Long {
    var size: Long = 0

    val uri = document.uri
    if (uri == null) return 0

    fun BuildPath(): String {
        var p = Uri.decode(uri.toString())
        p = p.substring(p.lastIndexOf(":") + 1)
        return@BuildPath p
    }
    val path = BuildPath()

    val list = getSAFFileList(context, uri, path)
    list.forEach {
        if (it.isDirectory)
            size += SAFDirectorySize(context, it)
        else
            size += it.size
    }
    return size
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
fun fromDocumentsContractChild(
    paramUri: Uri?,
    paramCursor: Cursor
): DocumentFileMeta {
    val documentFileMeta = DocumentFileMeta()
    val str = paramCursor.getString(0)
    documentFileMeta.parentUri = paramUri
    documentFileMeta.uri = DocumentsContract.buildDocumentUriUsingTree(paramUri, str)
    documentFileMeta.name = paramCursor.getString(1)
    documentFileMeta.size = paramCursor.getLong(2)
    documentFileMeta.timestamp = paramCursor.getLong(3)
    documentFileMeta.mime = paramCursor.getString(4)
    documentFileMeta.isDirectory = "vnd.android.document/directory" == documentFileMeta.mime
    documentFileMeta.canWrite = (paramCursor.getInt(5) and 0x0E) != 0
    return documentFileMeta
}