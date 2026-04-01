package com.reactnativereadium.reader

import android.net.Uri
import android.provider.OpenableColumns
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.util.RNLog
import com.reactnativereadium.utils.LinkOrLocator
import java.io.File
import java.util.Locale
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.FileExtension
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.FormatHints
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser


class ReaderService(
  private val reactContext: ReactApplicationContext
) {
  private val httpClient = DefaultHttpClient()
  private val assetRetriever = AssetRetriever(
    reactContext.contentResolver,
    httpClient
  )
  private val publicationOpener = PublicationOpener(
    publicationParser = DefaultPublicationParser(
      context = reactContext,
      assetRetriever = assetRetriever,
      httpClient = httpClient,
      pdfFactory = null,
    )
  )

  fun locatorFromLinkOrLocator(
    location: LinkOrLocator?,
    publication: Publication,
  ): Locator? {

    if (location == null) return null

    when (location) {
      is LinkOrLocator.Link -> {
        return publication.locatorFromLink(location.link)
      }
      is LinkOrLocator.Locator -> {
        return location.locator
      }
    }

    return null
  }

  suspend fun openPublication(
    fileName: String,
    initialLocation: LinkOrLocator?,
    callback: suspend (fragment: BaseReaderFragment) -> Unit
  ) {
    val publicationUrl: AbsoluteUrl
    val bookId: String
    val fileExtension: String?

    if (fileName.startsWith("content://")) {
      val androidUri = Uri.parse(fileName)
      publicationUrl = androidUri.toAbsoluteUrl() ?: run {
        RNLog.e(reactContext, "Invalid content URI: $fileName")
        return
      }
      val displayName = reactContext.contentResolver.query(
        androidUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
      )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
      } ?: fileName.substringAfterLast('/')
      bookId = displayName.substringBeforeLast('.')
      fileExtension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    } else {
      val publicationFile = File(fileName).absoluteFile
      if (!publicationFile.exists()) {
        RNLog.e(reactContext, "Failed to open publication: File does not exist: $fileName")
        return
      }
      publicationUrl = runCatching { publicationFile.toUrl() }
        .onFailure { RNLog.e(reactContext, "Invalid publication path: $fileName - ${it.message}") }
        .getOrNull()
        ?.let { it as? AbsoluteUrl } ?: run {
          RNLog.e(reactContext, "Could not convert file path to AbsoluteUrl: $fileName")
          return
        }
      bookId = publicationFile.nameWithoutExtension
      fileExtension = publicationFile.extension.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
    }

    val asset = assetRetriever
      .retrieve(
        publicationUrl,
        FormatHints(fileExtension = fileExtension?.let { FileExtension(it) })
      )
      .onFailure {
        RNLog.w(reactContext, "Unable to retrieve publication asset: ${it.message}")
      }
      .getOrNull()
      ?: return

    publicationOpener
      .open(
        asset = asset,
        allowUserInteraction = false
      )
      .onSuccess {
        val locator = locatorFromLinkOrLocator(initialLocation, it)
        if (!it.conformsTo(Publication.Profile.EPUB)) {
          RNLog.w(reactContext, "Unsupported publication format")
          return@onSuccess
        }
        val readerFragment = EpubReaderFragment.newInstance().also { frag ->
          frag.initFactory(it, locator, bookId)
        }
        callback.invoke(readerFragment)
      }
      .onFailure {
        RNLog.w(
          reactContext,
          "Error executing ReaderService.openPublication: ${it.message}"
        )
        // TODO: implement failure event
      }
  }

  sealed class Event {

    class ImportPublicationFailed(val errorMessage: String?) : Event()

    object UnableToMovePublication : Event()

    object ImportPublicationSuccess : Event()

    object ImportDatabaseFailed : Event()

    class OpenBookError(val errorMessage: String?) : Event()
  }
}
