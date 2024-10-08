package com.myAllVideoBrowser.ui.main.home.browser

import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.ui.main.history.HistoryViewModel
import com.myAllVideoBrowser.ui.main.settings.SettingsViewModel
import com.myAllVideoBrowser.util.AdBlockerHelper
import com.myAllVideoBrowser.util.FaviconUtils
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.IVideoDetector
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabViewModel
import com.myAllVideoBrowser.util.CookieUtils
import com.myAllVideoBrowser.util.SingleLiveEvent
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class CustomWebViewClient(
    private val tabViewModel: WebTabViewModel,
    private val mainActivity: MainActivity,
    private val settingsModel: SettingsViewModel,
    private val videoDetectionModel: IVideoDetector,
    private val historyModel: HistoryViewModel,
    private val proxyController: CustomProxyController,
    private val okHttpProxyClient: OkHttpProxyClient,
    private val updateTabEvent: SingleLiveEvent<WebTab>,
    private val pageTabProvider: PageTabProvider,
) : WebViewClient() {
    var videoAlert: MaterialAlertDialogBuilder? = null
    private var searchEventCounter = 0
    private var lastSavedHistoryUrl: String = ""
    private var lastSavedTitleHistory: String = ""
    private var lastRegularCheckUrl = ""
    private val regularJobsStorage: MutableMap<String, List<Disposable>> = mutableMapOf()

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        val viewTitle = view?.title
        val title = tabViewModel.currentTitle.get()
        val userAgent = view?.settings?.userAgentString ?: tabViewModel.userAgent.get()

        if (url != null && lastSavedHistoryUrl != url) {
            historyModel.viewModelScope.launch(historyModel.executorSingleHistory) {
                val icon = try {
                    FaviconUtils.getEncodedFaviconFromUrl(
                        okHttpProxyClient.getProxyOkHttpClient(), url
                    )
                } catch (e: Throwable) {
                    null
                }
                saveUrlToHistory(url, icon, viewTitle ?: title)

                videoDetectionModel.onStartPage(
                    url,
                    userAgent
                        ?: BrowserFragment.MOBILE_USER_AGENT
                )
                tabViewModel.onUpdateVisitedHistory(
                    url,
                    title,
                    userAgent
                )
            }
        }
        super.doUpdateVisitedHistory(view, url, isReload)
    }

    // TODO handle for proxy and others
//    override fun onReceivedHttpAuthRequest(
//        view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?
//    ) {
//        val creds = proxyController.getProxyCredentials()
//        handler?.proceed(creds.first, creds.second)
//    }

    override fun shouldInterceptRequest(
        view: WebView?, request: WebResourceRequest?
    ): WebResourceResponse? {
        val isAdBlockerOn = settingsModel.isAdBlocker.get()
        val url = request?.url.toString()

        val isUrlAd: Boolean = isAdBlockerOn && tabViewModel.isAd(url)

        when {
            isUrlAd -> {
                return AdBlockerHelper.createEmptyResource()
            }

            url.contains(".m3u8") || url.contains(".mpd") || url.contains(".txt") -> {
                if (request != null) {
                    val verReq = try {
                        CookieUtils.webRequestToHttpWithCookies(request)
                    } catch (e: Throwable) {
                        null
                    }
                    if (verReq != null) {
                        videoDetectionModel.verifyLinkStatus(
                            verReq, tabViewModel.currentTitle.get()
                        )
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            else -> {
                if (settingsModel.getIsCheckEveryRequestOnVideo().get()) {
                    val requestWithCookies = request?.let { resourceRequest ->
                        CookieUtils.webRequestToHttpWithCookies(
                            resourceRequest
                        )
                    }
                    val disposable = videoDetectionModel.checkRegularMp4(requestWithCookies)

                    val currentUrl = tabViewModel.getTabTextInput().get() ?: ""
                    if (currentUrl != lastRegularCheckUrl) {
                        regularJobsStorage[lastRegularCheckUrl]?.forEach {
                            it.dispose()
                        }
                        regularJobsStorage.remove(lastRegularCheckUrl)
                        lastRegularCheckUrl = currentUrl
                    }
                    if (disposable != null) {
                        val overall = mutableListOf<Disposable>()
                        overall.addAll(regularJobsStorage[currentUrl]?.toList() ?: emptyList())
                        overall.add(disposable)
                        regularJobsStorage[currentUrl] = overall
                    }
                }
                return super.shouldInterceptRequest(
                    view, request
                )
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)

        videoAlert = null
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())
        val headers = pageTab.getHeaders() ?: emptyMap()
        val favi = pageTab.getFavicon() ?: view.favicon ?: favicon

        updateTabEvent.value = WebTab(
            url,
            view.title,
            favi,
            headers,
            view,
            id = pageTab.id
        )
        tabViewModel.onStartPage(url, view.title)
    }


    override fun shouldOverrideUrlLoading(view: WebView, url: WebResourceRequest): Boolean {
        val isAdBlockerOn = settingsModel.isAdBlocker.get()
        val isAd = if (isAdBlockerOn) tabViewModel.isAd(url.url.toString()) else false

        return if (url.url.toString().startsWith("http") && url.isForMainFrame && !isAd) {
            if (!tabViewModel.isTabInputFocused.get()) {
                tabViewModel.setTabTextInput(url.url.toString())
            }
            false
        } else {
            true
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        tabViewModel.finishPage(url)
    }

    override fun onRenderProcessGone(
        view: WebView?, detail: RenderProcessGoneDetail?
    ): Boolean {
        val pageTab = pageTabProvider.getPageTab(tabViewModel.thisTabIndex.get())

        val webView = pageTab.getWebView()
        if (view == webView && detail?.didCrash() == true) {
            webView?.destroy()
            return true
        }

        return super.onRenderProcessGone(view, detail)
    }

    private suspend fun saveUrlToHistory(url: String, favicon: Bitmap?, title: String?) {
        val isTitleEmpty = title?.trim()?.isEmpty() == true

        if (!isTitleEmpty && lastSavedTitleHistory != title && lastSavedHistoryUrl != url && url.isNotEmpty() && !url.contains(
                "about:blank"
            )
        ) {
            lastSavedHistoryUrl = url
            lastSavedTitleHistory = title ?: ""

            val outputFavicon = FaviconUtils.bitmapToBytes(favicon)

            yield()

            historyModel.saveHistory(
                HistoryItem(
                    url = url, favicon = outputFavicon, title = title
                )
            )

            ++searchEventCounter
            if (searchEventCounter % 3 == 0) {
                withContext(Dispatchers.Main) {
                    mainActivity.mainViewModel.showInterstitialAdEvent.call()
                }
            }
        }
    }
}