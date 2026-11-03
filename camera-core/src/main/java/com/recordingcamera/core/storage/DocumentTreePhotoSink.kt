package com.recordingcamera.core.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.recordingcamera.core.CameraError
import com.recordingcamera.core.CameraLens
import com.recordingcamera.core.MediaDestination
import com.recordingcamera.core.SavedPhoto
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores stills inside a user-picked storage access framework tree.
 *
 * Uses [DocumentsContract] directly instead of `DocumentFile` so the module does not need the
 * extra `androidx.documentfile` dependency. The caller is responsible for holding a persistable
 * read/write grant on [MediaDestination.DocumentTree.treeUri].
 */
internal class DocumentTreePhotoSink(
    private val context: Context,
    private val destination: MediaDestination.DocumentTree,
) : PhotoSink {

    override suspend fun store(
        source: File,
        displayName: String,
        lens: CameraLens,
        capturedAtMillis: Long,
    ): SavedPhoto = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (width, height) = MediaProbe.imageSize(source)
        val folder = findOrCreateFolder(resolver)
        val fileUri = DocumentsContract.createDocument(resolver, folder, MIME_TYPE, displayName)
            ?: throw CameraError.StorageFailed(displayName, IOException("createDocument returned null"))

        try {
            resolver.openOutputStream(fileUri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: throw IOException("could not open $fileUri for writing")
            source.delete()
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, fileUri) }
            throw CameraError.StorageFailed(displayName, error)
        }

        SavedPhoto(
            uri = fileUri,
            displayName = displayName,
            sizeBytes = sourceLength(resolver, fileUri),
            width = width,
            height = height,
            capturedAtMillis = capturedAtMillis,
            lens = lens,
        )
    }

    override suspend fun discard(photo: SavedPhoto) = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, photo.uri) }
        Unit
    }

    private fun sourceLength(resolver: ContentResolver, uri: Uri): Long =
        runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L }
            .getOrDefault(0L)

    private fun findOrCreateFolder(resolver: ContentResolver): Uri {
        val treeUri = destination.treeUri
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)

        findChild(resolver, treeUri, rootDocumentId, destination.folderName, isDirectory = true)
            ?.let { return it }

        return DocumentsContract.createDocument(
            resolver,
            rootDocument,
            DocumentsContract.Document.MIME_TYPE_DIR,
            destination.folderName,
        ) ?: throw CameraError.StorageFailed(
            destination.folderName,
            IOException("cannot create folder in $treeUri"),
        )
    }

    private fun findChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
        displayName: String,
        isDirectory: Boolean,
    ): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                val mimeType = cursor.getString(2) ?: continue
                val mimeMatches = if (isDirectory) {
                    mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                } else {
                    mimeType.startsWith("image/")
                }
                if (name == displayName && mimeMatches) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        cursor.getString(0),
                    )
                }
            }
        }
        return null
    }

    companion object {
        const val MIME_TYPE = "image/jpeg"
    }
}
