package com.google.android.exoplayer2.demo

import android.content.Context
import android.os.Build
import android.support.annotation.RequiresApi
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.drm.FrameworkMediaDrm
import com.google.android.exoplayer2.drm.OfflineLicenseHelper
import com.google.android.exoplayer2.source.dash.DashUtil
import com.google.android.exoplayer2.upstream.*
import com.kaltura.dtg.ContentManager
import com.kaltura.dtg.DownloadItem
import com.kaltura.dtg.DownloadState
import com.kaltura.dtg.DownloadStateListener
import java.io.*
import java.lang.Exception
import java.util.*
import java.nio.charset.Charset


/**
 * Test DASH offline download
 * Created by adheus on 29/03/17.
 */

@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
data class DRMLicenseFecther(val context: Context, val url: String, val licensingUri: String,
                             val merchant: String,  val assetId: String? = null,
                             val userId: String, val sessionId: String, val dataSourceFactory: HttpDataSource.Factory,
                             val variantId: String? = null, val authToken: String? = null) {


    fun download() {
        Thread(Runnable { startDownload() }).start()
    }

    private fun startDownload() {
        val DEBUG_TAG = "DEBUG DO DD-EU"
        try {
            val dashManifest = DashUtil.loadManifest(dataSourceFactory.createDataSource(), url)
            val drmInitData = DashUtil.loadDrmInitData(dataSourceFactory.createDataSource(), dashManifest)
            Log.d(DEBUG_TAG, "Loaded manifest: " + dashManifest.toString())
            val castLabsCallback = CastlabsWidevineDrmCallback(licensingUri, merchant, assetId, userId, sessionId, dataSourceFactory)
            val offlineDRMHelper = OfflineLicenseHelper(FrameworkMediaDrm.newInstance(C.WIDEVINE_UUID), castLabsCallback, null)

            val licenseKeySetId = offlineDRMHelper.downloadLicense(drmInitData)
            Manager.saveLicenseForOfflineVideo(context, userId, licenseKeySetId)
            Log.d(DEBUG_TAG, "Saved license with keySetId: " + licenseKeySetId.toString())
            val remainingSeconds = offlineDRMHelper.getLicenseDurationRemainingSec(licenseKeySetId)
            Log.d(DEBUG_TAG, "Remaining time:  " + remainingSeconds.first.toString() + " s / Playback duration: " + remainingSeconds.second.toString() + " s")


            val contentManager = ContentManager.getInstance(context)
            contentManager.setMaxConcurrentDownloads(2)
            val downloadStartTime = HashMap<String, Long>()

            contentManager.addDownloadStateListener(object : DownloadStateListener {
                override fun onDownloadComplete(item: DownloadItem) {
                    val startTime = downloadStartTime.remove(item.itemId)!!
                    val downloadTime = System.currentTimeMillis() - startTime
                    Log.d(DEBUG_TAG, "onDownloadComplete: " + item.itemId + "; " + item.downloadedSizeBytes / 1024 + "; " + downloadTime / 1000f)
                }

                override fun onDownloadStart(item: DownloadItem) {
                    downloadStartTime.put(item.itemId, System.currentTimeMillis())
                    Log.d(DEBUG_TAG, "onDownloadStart: " + item.itemId + "; " + item.downloadedSizeBytes / 1024)
                }

                override fun onDownloadPause(item: DownloadItem) {
                    Log.d(DEBUG_TAG, "onDownloadPause: " + item.itemId + "; " + item.downloadedSizeBytes / 1024)
                }

                override fun onDownloadMetadata(item: DownloadItem, error: Exception?) {
                    if (error == null) {
                        Log.d(DEBUG_TAG, "Metadata for " + item.itemId + " is loaded")
                        Log.d(DEBUG_TAG, item.toString())


                        // Pre-download interactive track selection
                        // Select second audio track, if there are at least 2.

                    val trackSelector = item.trackSelector
                    if (trackSelector != null) {
                        val audioTracks = trackSelector.getAvailableTracks(DownloadItem.TrackType.AUDIO)
                        if (audioTracks.size > 0) {
                            trackSelector.setSelectedTracks(DownloadItem.TrackType.AUDIO, Collections.singletonList(audioTracks[0]))
                        }

                        val videoTracks = trackSelector.getAvailableTracks(DownloadItem.TrackType.VIDEO)
                        if (videoTracks.size > 0) {
                            val minVideo = Collections.min(videoTracks, DownloadItem.Track.bitrateComparator)
                            trackSelector.setSelectedTracks(DownloadItem.TrackType.VIDEO, Collections.singletonList(minVideo))
                        }

                        try {
                            trackSelector.apply()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }

                        onDashSelectedTracks(item)
                    }
                    } else {
                        Log.d(DEBUG_TAG, "Failed loading metadata for " + item.itemId + ": " + error)
                    }
                }

                override fun onProgressChange(item: DownloadItem, downloadedBytes: Long) {
                    val estimatedSizeBytes = item.estimatedSizeBytes
                    val percent = (if (estimatedSizeBytes > 0) 100 * downloadedBytes / estimatedSizeBytes else 0).toLong()
                    Log.d(DEBUG_TAG, "onProgressChange: " + item.itemId + "; " + percent + "; " + item.downloadedSizeBytes / 1024)
                }

                override fun onDownloadStop(item: DownloadItem) {
                    Log.d(DEBUG_TAG, "onDownloadStop: " + item.itemId + "; " + item.downloadedSizeBytes / 1024)
                }

                override fun onTracksAvailable(item: DownloadItem, trackSelector: DownloadItem.TrackSelector) {
                    // Policy-based selection
                    Log.d(DEBUG_TAG, "onTracksAvailable")

                    // Select first and last audio
                    val audioTracks = trackSelector.getAvailableTracks(DownloadItem.TrackType.AUDIO)
                    if (audioTracks.size > 0) {
                        val selection = ArrayList<DownloadItem.Track>()
                        selection.add(audioTracks[0])
                        if (audioTracks.size > 1) {
                            selection.add(audioTracks[audioTracks.size - 1])
                        }
                        trackSelector.setSelectedTracks(DownloadItem.TrackType.AUDIO, selection)
                    }

                    // Select lowest-resolution video
                    val videoTracks = trackSelector.getAvailableTracks(DownloadItem.TrackType.VIDEO)
                    val minVideo = Collections.min(videoTracks, DownloadItem.Track.bitrateComparator)
                    trackSelector.setSelectedTracks(DownloadItem.TrackType.VIDEO, Collections.singletonList(minVideo))

                    //onDashTracksReady(item)
                }

            })

            val downloadID = userId
            contentManager.start {
                Log.d(DEBUG_TAG, "Service started")

                var mDashDownload = contentManager.findItem(downloadID)

                if (mDashDownload == null) {

                    Log.d(DEBUG_TAG, "Creating download for: " + url)
                    mDashDownload = contentManager.createItem(downloadID, url)
                }
                if (mDashDownload.state != DownloadState.COMPLETED) {
                    mDashDownload.loadMetadata()
                }
            }
        } catch (e: Exception) {
            Log.d(DEBUG_TAG, "Offline DRM configuration failed: " + e.message)
            ContentManager.getInstance(context).start {  Log.d(DEBUG_TAG, "Service started") }
        }
    }

    private fun onDashSelectedTracks(item: DownloadItem) {
        if (item.state == DownloadState.INFO_LOADED) {
            item.startDownload()
        }
    }

    private fun getLocalFilePath(context: Context, downloadId:String) : String {
        return ContentManager.getInstance(context).getLocalFile(downloadId).absolutePath
    }

    object Manager {

        fun  saveLicenseForOfflineVideo(context: Context, userId: String, licenseKeySetId: ByteArray) {
            val sharedPref = context.getSharedPreferences("offline_drm", Context.MODE_PRIVATE)
            val editor = sharedPref.edit()
            editor.putString(userId, String(licenseKeySetId, Charset.forName("UTF-8")))
            editor.apply()
        }

        fun  getLicenseForOfflineVideoId(context: Context, userId: String) : ByteArray? {
            val sharedPref = context.getSharedPreferences("offline_drm", Context.MODE_PRIVATE)
            val stringLicenseKey = sharedPref.getString(userId, null)
            return stringLicenseKey?.toByteArray(Charset.forName("UTF-8"))
        }
    }
}
