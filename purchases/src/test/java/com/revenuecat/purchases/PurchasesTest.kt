//  Purchases
//
//  Copyright © 2019 RevenueCat, Inc. All rights reserved.
//

package com.revenuecat.purchases

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchaseHistoryRecord
import com.android.billingclient.api.SkuDetails
import com.revenuecat.purchases.attributes.SubscriberAttributesManager
import com.revenuecat.purchases.caching.DeviceCache
import com.revenuecat.purchases.interfaces.Callback
import com.revenuecat.purchases.interfaces.GetSkusResponseListener
import com.revenuecat.purchases.interfaces.ReceivePurchaserInfoListener
import com.revenuecat.purchases.interfaces.UpdatedPurchaserInfoListener
import com.revenuecat.purchases.util.AdvertisingIdClient
import io.mockk.Call
import io.mockk.MockKAnswerScope
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.AssertionsForClassTypes
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.ArrayList
import java.util.Collections.emptyList
import java.util.ConcurrentModificationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.net.URL

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class PurchasesTest {

    private val mockBillingWrapper: BillingWrapper = mockk()
    private val mockBackend: Backend = mockk()
    private val mockCache: DeviceCache = mockk()
    private val updatedPurchaserInfoListener: UpdatedPurchaserInfoListener = mockk()
    private val mockApplication = mockk<Application>(relaxed = true)
    private val mockContext = mockk<Context>(relaxed = true).apply {
        every {
            applicationContext
        } returns mockApplication
    }
    private val mockIdentityManager = mockk<IdentityManager>()
    private val mockSubscriberAttributesManager = mockk<SubscriberAttributesManager>()

    private var capturedPurchasesUpdatedListener = slot<BillingWrapper.PurchasesUpdatedListener>()
    private var capturedBillingWrapperStateListener = slot<BillingWrapper.StateListener>()
    private var capturedConsumeResponseListener = slot<(BillingResult, String) -> Unit>()
    private var capturedAcknowledgeResponseListener = slot<(BillingResult, String) -> Unit>()

    private val randomAppUserId = "\$RCAnonymousID:ff68f26e432648369a713849a9f93b58"
    private val appUserId = "fakeUserID"
    private val adID = "123"
    private lateinit var purchases: Purchases
    private var receivedSkus: List<SkuDetails>? = null
    private var receivedOfferings: Offerings? = null
    private var mockExecutorService = mockk<ExecutorService>().apply {
        val capturedRunnable = slot<Runnable>()
        every { execute(capture(capturedRunnable)) } answers { capturedRunnable.captured.run() }
    }

    private val stubOfferingIdentifier = "offering_a"
    private val stubProductIdentifier = "monthly_freetrial"
    private val oneOfferingsResponse = "{'offerings': [" +
        "{'identifier': '$stubOfferingIdentifier', " +
        "'description': 'This is the base offering', " +
        "'packages': [" +
        "{'identifier': '\$rc_monthly','platform_product_identifier': '$stubProductIdentifier'}" +
        "]}]," +
        "'current_offering_id': '$stubOfferingIdentifier'}"

    private val mockLifecycle= mockk<Lifecycle>()
    private val mockLifecycleOwner = mockk<LifecycleOwner>()

    @Before
    fun setupStatic() {
        mockkStatic("com.revenuecat.purchases.FactoriesKt")
        mockkStatic(ProcessLifecycleOwner::class)
    }

    @After
    fun tearDown() {
        Purchases.backingFieldSharedInstance = null
        Purchases.postponedAttributionData = mutableListOf()
    }

    private fun setup(
        errorGettingPurchaserInfo: PurchasesError? = null,
        anonymous: Boolean = false
    ): PurchaserInfo {
        val mockInfo = mockk<PurchaserInfo>()
        val skus = listOf(stubProductIdentifier)
        val userIdToUse = if (anonymous) randomAppUserId else appUserId

        mockCache(mockInfo)
        mockBackend(mockInfo, errorGettingPurchaserInfo)
        mockBillingWrapper()
        mockSkuDetails(skus, skus, PurchaseType.SUBS)

        every {
            updatedPurchaserInfoListener.onReceived(any())
        } just Runs
        every {
            mockIdentityManager.configure(any())
        } just Runs
        every {
            mockIdentityManager.currentAppUserID
        } returns userIdToUse
        every {
            mockIdentityManager.currentUserIsAnonymous()
        } returns anonymous
        buildPurchases(anonymous)
        mockSubscriberAttributesManager(userIdToUse)

        return mockInfo
    }

    private fun stubOfferings(sku: String): Pair<SkuDetails, Offerings> {
        val skuDetails = mockk<SkuDetails>().also {
            every { it.sku } returns sku
            every { it.type } returns BillingClient.SkuType.SUBS
        }
        val jsonObject = JSONObject(oneOfferingsResponse)
        val packageObject = Package(
            "\$rc_monthly",
            PackageType.MONTHLY,
            skuDetails,
            stubOfferingIdentifier
        )
        val offering = Offering(
            stubOfferingIdentifier,
            "This is the base offering",
            listOf(packageObject)
        )
        val offerings = Offerings(
            offering,
            mapOf(offering.identifier to offering)
        )
        every {
            jsonObject.createOfferings(any())
        } returns offerings
        return Pair(skuDetails, offerings)
    }

    @Test
    fun canBeCreated() {
        setup()
        assertThat(purchases).isNotNull
    }

    @Test
    fun getsSubscriptionSkus() {
        setup()

        val skus = listOf("onemonth_freetrial")

        val skuDetails = mockSkuDetails(skus, listOf(), PurchaseType.SUBS)

        purchases.getSubscriptionSkus(skus,
            object : GetSkusResponseListener {
                override fun onReceived(skus: MutableList<SkuDetails>) {
                    receivedSkus = skus
                }

                override fun onError(error: PurchasesError) {
                    fail("shouldn't be error")
                }
            })

        assertThat(receivedSkus).isEqualTo(skuDetails)
    }

    @Test
    fun getsNonSubscriptionSkus() {
        setup()

        val skus = listOf("normal_purchase")

        val skuDetails = mockSkuDetails(skus, listOf(), PurchaseType.INAPP)

        purchases.getNonSubscriptionSkus(skus,
            object : GetSkusResponseListener {
                override fun onReceived(skus: MutableList<SkuDetails>) {
                    receivedSkus = skus
                }

                override fun onError(error: PurchasesError) {
                    fail("shouldn't be error")
                }
            })

        assertThat(receivedSkus).isEqualTo(skuDetails)
    }

    @Test
    fun canMakePurchase() {
        setup()

        val activity: Activity = mockk()
        val sku = "onemonth_freetrial"
        val skuDetails = mockk<SkuDetails>().also {
            every { it.sku } returns sku
        }

        purchases.purchaseProductWith(
            activity,
            skuDetails
        ) { _, _ -> }

        verify {
            mockBillingWrapper.makePurchaseAsync(
                eq(activity),
                eq(appUserId),
                skuDetails,
                null,
                null
            )
        }
    }

    @Test
    fun canMakePurchaseOfAPackage() {
        setup()

        val activity: Activity = mockk()
        val (skuDetails, offerings) = stubOfferings("onemonth_freetrial")

        purchases.purchasePackageWith(
            activity,
            offerings[stubOfferingIdentifier]!!.monthly!!
        ) { _, _ -> }

        verify {
            mockBillingWrapper.makePurchaseAsync(
                eq(activity),
                eq(appUserId),
                skuDetails,
                null,
                stubOfferingIdentifier
            )
        }
    }

    @Test
    fun canMakePurchaseUpgradeOfAPackage() {
        setup()

        val activity: Activity = mockk()
        val (skuDetails, offerings) = stubOfferings("onemonth_freetrial")

        val oldPurchase = mockk<PurchaseHistoryRecordWrapper>()
        every { oldPurchase.sku } returns "oldSku"
        every { oldPurchase.type } returns PurchaseType.SUBS

        every {
            mockBillingWrapper.findPurchaseInPurchaseHistory(PurchaseType.SUBS.toSKUType()!!, "oldSku", captureLambda())
        } answers {
            lambda<(BillingResult, PurchaseHistoryRecordWrapper?) -> Unit>().captured.invoke(BillingResult(), oldPurchase)
        }

        purchases.purchasePackageWith(
            activity,
            offerings[stubOfferingIdentifier]!!.monthly!!,
            UpgradeInfo(oldPurchase.sku)
        ) { _, _ -> }

        verify {
            mockBillingWrapper.makePurchaseAsync(
                eq(activity),
                eq(appUserId),
                skuDetails,
                ReplaceSkuInfo(oldPurchase),
                stubOfferingIdentifier
            )
        }
    }

    @Test
    fun postsSuccessfulPurchasesToBackend() {
        val mockInfo = setup()
        val sku = "inapp"
        val purchaseToken = "token_inapp"
        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        val productInfo = mockPostReceipt(
            sku,
            purchaseToken,
            observerMode = false,
            mockInfo = mockInfo,
            offeringIdentifier = null,
            type = PurchaseType.INAPP
        )
        val productInfo1 = mockPostReceipt(
            skuSub,
            purchaseTokenSub,
            observerMode = false,
            mockInfo = mockInfo,
            offeringIdentifier = "offering_a",
            type = PurchaseType.SUBS
        )
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.INAPP) +
                getMockedPurchaseList(skuSub, purchaseTokenSub, PurchaseType.SUBS, "offering_a")
        )

        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }

        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseTokenSub,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo1,
                onSuccess = any(),
                onError = any()
            )
        }

        verify (exactly = 1){
            mockBillingWrapper.consumePurchase(purchaseToken, any())
        }

        verify (exactly = 0){
            mockBillingWrapper.consumePurchase(purchaseTokenSub, any())
        }
    }

    @Test
    fun callsPostForEachUpdatedPurchase() {
        setup()

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        val productInfos = listOf(
            mockQueryingSkuDetails(skuSub, PurchaseType.SUBS, null),
            mockQueryingSkuDetails(sku, PurchaseType.INAPP, null)
        )

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.INAPP) +
                getMockedPurchaseList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
        )

        productInfos.forEach {
            verify(exactly = 1) {
                mockBackend.postReceiptData(
                    purchaseToken = any(),
                    appUserID = appUserId,
                    isRestore = false,
                    observerMode = false,
                    subscriberAttributes = emptyMap(),
                    productInfo = it,
                    onSuccess = any(),
                    onError = any()
                )
            }
        }
    }

    @Test
    fun doesntPostIfNotOK() {
        setup()

        capturedPurchasesUpdatedListener.captured.onPurchasesFailedToUpdate(emptyList(), 0, "fail")
        verify(exactly = 0) {
            mockBackend.postReceiptData(
                purchaseToken = any(),
                appUserID = any(),
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = any(),
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun passesUpErrors() {
        setup()
        var errorCalled = false
        purchases.purchaseProductWith(
            mockk(),
            mockk<SkuDetails>().also {
                every { it.sku } returns "sku"
            },
            onError = { error, _ ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
            }, onSuccess = { _, _ -> })

        val purchase = mockk<Purchase>(relaxed = true)
        every { purchase.sku } returns "sku"
        capturedPurchasesUpdatedListener.captured.onPurchasesFailedToUpdate(listOf(purchase), 2, "")
        assertThat(errorCalled).isTrue()
    }

    @Test
    fun doesNotGetSubscriberInfoOnCreated() {
        setup()

        verify (exactly = 0){
            mockBackend.getPurchaserInfo(eq(appUserId), any(), any())
        }
    }

    @Test
    fun `fetch purchaser info on foregrounded if it's stale`() {
        setup()
        mockCacheStale(true)
        mockSuccessfulQueryPurchases(
            queriedSUBS = emptyMap(),
            queriedINAPP = emptyMap(),
            notInCache = emptyList()
        )
        mockSynchronizeSubscriberAttributesForAllUsers()
        Purchases.sharedInstance.onAppForegrounded()
        verify (exactly = 1) {
            mockBackend.getPurchaserInfo(eq(appUserId), any(), any())
        }
    }

    @Test
    fun `fetch offerings on foregrounded if it's stale`() {
        setup()
        mockCacheStale(offeringsStale = true)
        mockSuccessfulQueryPurchases(
            queriedSUBS = emptyMap(),
            queriedINAPP = emptyMap(),
            notInCache = emptyList()
        )
        mockSynchronizeSubscriberAttributesForAllUsers()
        Purchases.sharedInstance.onAppForegrounded()
        verify (exactly = 1) {
            mockBackend.getOfferings(eq(appUserId), any(), any())
        }
    }

    private fun mockSynchronizeSubscriberAttributesForAllUsers() {
        every {
            mockSubscriberAttributesManager.synchronizeSubscriberAttributesForAllUsers(appUserId)
        } just Runs
    }

    @Test
    fun `does not fetch purchaser info on foregrounded if it's not stale`() {
        setup()
        mockCacheStale()
        mockSuccessfulQueryPurchases(
            queriedSUBS = emptyMap(),
            queriedINAPP = emptyMap(),
            notInCache = emptyList()
        )
        mockSynchronizeSubscriberAttributesForAllUsers()
        Purchases.sharedInstance.onAppForegrounded()
        verify (exactly = 0){
            mockBackend.getPurchaserInfo(eq(appUserId), any(), any())
        }
    }

    @Test
    fun `does not fetch offerings on foregrounded if it's not stale`() {
        setup()
        mockCacheStale()
        mockSuccessfulQueryPurchases(
            queriedSUBS = emptyMap(),
            queriedINAPP = emptyMap(),
            notInCache = emptyList()
        )
        mockSynchronizeSubscriberAttributesForAllUsers()
        Purchases.sharedInstance.onAppForegrounded()
        verify (exactly = 0){
            mockBackend.getOfferings(eq(appUserId), any(), any())
        }
    }

    @Test
    fun canBeSetupWithoutAppUserID() {
        setup(anonymous = true)
        assertThat(purchases).isNotNull

        assertThat(purchases.appUserID).isEqualTo(randomAppUserId)
    }

    @Test
    fun isRestoreWhenUsingNullAppUserID() {
        setup(anonymous = true)

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        val productInfo = mockQueryingSkuDetails(sku, PurchaseType.SUBS, null)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.SUBS)
        )
        verify {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = randomAppUserId,
                isRestore = true,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun doesntRestoreNormally() {
        setup()

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        val productInfo = mockQueryingSkuDetails(sku, PurchaseType.SUBS, null)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.SUBS)
        )

        verify {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun canOverrideAnonMode() {
        setup()

        purchases.allowSharingPlayStoreAccount = true

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        val productInfo = mockQueryingSkuDetails(sku, PurchaseType.SUBS, null)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.SUBS)
        )

        verify {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = true,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun restoringPurchasesGetsHistory() {
        setup()
        var capturedLambda: ((List<PurchaseHistoryRecordWrapper>) -> Unit)? = null
        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            capturedLambda = lambda<(List<PurchaseHistoryRecordWrapper>) -> Unit>().captured.also {
                it.invoke(listOf(mockk(relaxed = true)))
            }
        }

        purchases.restorePurchasesWith { }

        assertThat(capturedLambda).isNotNull
        verify {
            mockBillingWrapper.queryAllPurchases(
                any(),
                any()
            )
        }
    }

    @Test
    fun historicalPurchasesPassedToBackend() {
        setup()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        var capturedLambda: ((List<PurchaseHistoryRecordWrapper>) -> Unit)? = null
        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            capturedLambda = lambda<(List<PurchaseHistoryRecordWrapper>) -> Unit>().captured
            capturedLambda?.invoke(
                getMockedPurchaseHistoryList(sku, purchaseToken, PurchaseType.INAPP) +
                    getMockedPurchaseHistoryList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
            )
        }

        var restoreCalled = false
        purchases.restorePurchasesWith(onSuccess = {
            restoreCalled = true
        }, onError = {
            fail("Should not be an error")
        })
        assertThat(capturedLambda).isNotNull
        assertThat(restoreCalled).isTrue()

        val productInfo = ProductInfo(
            productID = sku
        )
        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = true,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }

        val productInfo1 = ProductInfo(
            productID = skuSub
        )
        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseTokenSub,
                appUserID = appUserId,
                isRestore = true,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo1,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun failedToRestorePurchases() {
        setup()
        val purchasesError = PurchasesError(PurchasesErrorCode.StoreProblemError, "Broken")
        every {
            mockBillingWrapper.queryAllPurchases(any(), captureLambda())
        } answers {
            lambda<(PurchasesError) -> Unit>().captured.invoke(purchasesError)
        }

        var onErrorCalled = false
        purchases.restorePurchasesWith(onSuccess = {
            fail("should be an error")
        }, onError = { error ->
            onErrorCalled = true
            assertThat(error).isEqualTo(purchasesError)
        })

        assertThat(onErrorCalled).isTrue()
    }

    @Test
    fun restoringCallsRestoreCallback() {
        setup()

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "onemonth_freetrial_sub"
        val purchaseTokenSub = "crazy_purchase_token_sub"

        var capturedLambda: ((List<PurchaseHistoryRecordWrapper>) -> Unit)? = null
        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            capturedLambda = lambda<(List<PurchaseHistoryRecordWrapper>) -> Unit>().captured.also {
                it.invoke(
                    getMockedPurchaseHistoryList(sku, purchaseToken, PurchaseType.INAPP) +
                        getMockedPurchaseHistoryList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
                )
            }
        }

        val mockInfo = mockk<PurchaserInfo>()
        every {
            mockBackend.postReceiptData(
                purchaseToken = any(),
                appUserID = any(),
                isRestore = true,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = any(),
                onSuccess = captureLambda(),
                onError = any()
            )
        } answers {
            lambda<PostReceiptDataSuccessCallback>().captured.invoke(mockInfo, emptyList())
        }

        var callbackCalled = false
        purchases.restorePurchasesWith(onSuccess = { info ->
            assertThat(mockInfo).isEqualTo(info)
            callbackCalled = true
        }, onError = {
            fail("should be success")
        })

        assertThat(capturedLambda).isNotNull
        verify(exactly = 1) {
            mockBillingWrapper.queryAllPurchases(any(), any())
        }

        assertThat(callbackCalled).isTrue()
    }

    @Test
    fun receivedPurchaserInfoShouldBeCached() {
        setup()

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        val productInfo = mockQueryingSkuDetails(sku, PurchaseType.SUBS, null)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.SUBS)
        )

        verify {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
        verify(exactly = 1) {
            mockCache.cachePurchaserInfo(
                any(),
                any()
            )
        }
    }

    @Test
    fun `if no cached offerings, backend is hit when getting offerings`() {
        setup()

        val skus = listOf(stubProductIdentifier)
        mockProducts()
        mockSkuDetails(skus, skus, PurchaseType.SUBS)
        val (_, offerings) = stubOfferings("onemonth_freetrial")

        every {
            mockCache.cachedOfferings
        } returns null
        every {
            mockCache.cacheOfferings(any())
        } just Runs

        purchases.getOfferingsWith({ fail("should be a success") }) {
            receivedOfferings = it
        }

        assertThat(receivedOfferings).isNotNull

        verify (exactly = 1) {
            mockBackend.getOfferings(any(), any(), any())
        }
        verify (exactly = 1) {
            mockCache.cacheOfferings(any())
        }
    }

    @Test
    fun `products are populated when getting offerings`() {
        setup()

        val skus = listOf(stubProductIdentifier)
        mockProducts()
        mockSkuDetails(skus, skus, PurchaseType.SUBS)
        val (_, offerings) = stubOfferings("onemonth_freetrial")

        every {
            mockCache.cachedOfferings
        } returns null
        every {
            mockCache.cacheOfferings(any())
        } just Runs

        purchases.getOfferingsWith({ fail("should be a success") }) {
            receivedOfferings = it
        }

        assertThat(receivedOfferings).isNotNull
        assertThat(receivedOfferings!!.all.size).isEqualTo(1)
        assertThat(receivedOfferings!![stubOfferingIdentifier]!!.monthly!!.product).isNotNull
    }

    @Test
    fun `if cached offerings are not stale`() {
        setup()

        mockProducts()
        mockSkuDetails(listOf(), listOf(), PurchaseType.SUBS)
        val (_, offerings) = stubOfferings("onemonth_freetrial")

        every {
            mockCache.cachedOfferings
        } returns offerings
        mockCacheStale(offeringsStale = true)

        purchases.getOfferingsWith({ fail("should be a success") }) {
            receivedOfferings = it
        }

        assertThat(receivedOfferings).isEqualTo(offerings)
    }

    @Test
    fun `if cached offerings are stale, call backend`() {
        setup()

        mockProducts()
        mockSkuDetails(listOf(), listOf(), PurchaseType.SUBS)
        val (_, offerings) = stubOfferings("onemonth_freetrial")

        every {
            mockCache.cachedOfferings
        } returns offerings
        mockCacheStale(offeringsStale = true)

        purchases.getOfferingsWith({ fail("should be a success") }) {
            receivedOfferings = it
        }

        assertThat(receivedOfferings).isEqualTo(offerings)
        verify (exactly = 1) {
            mockBackend.getOfferings(any(), any(), any())
        }
    }

    @Test
    fun getOfferingsIsCached() {
        setup()

        every {
            mockCache.cachedOfferings
        } returns null
        every {
            mockCache.cacheOfferings(any())
        } just Runs

        purchases.getOfferingsWith({ fail("should be a success") }) {
            receivedOfferings = it
        }

        assertThat(receivedOfferings).isNotNull
        verify {
            mockCache.cacheOfferings(any())
        }
    }

    @Test
    fun getOfferingsErrorIsNotCalledIfSkuDetailsMissing() {
        setup()
        every {
            mockCache.cachedOfferings
        } returns null

        val skus = listOf(stubProductIdentifier)
        mockProducts()
        mockSkuDetails(skus, ArrayList(), PurchaseType.SUBS)
        mockSkuDetails(skus, ArrayList(), PurchaseType.INAPP)

        val errorMessage = emptyArray<PurchasesError>()

        purchases.getOfferingsWith({ errorMessage[0] = it }) {
            receivedOfferings = it
        }

        assertThat(errorMessage.size).isZero()
        assertThat(this.receivedOfferings).isNotNull
    }

    @Test
    fun getOfferingsErrorIsCalledIfNoBackendResponse() {
        setup()

        every {
            mockCache.cachedOfferings
        } returns null
        every {
            mockCache.cacheOfferings(any())
        } just Runs

        every {
            mockBackend.getOfferings(
                any(),
                any(),
                captureLambda()
            )
        } answers {
            lambda<(PurchasesError) -> Unit>().captured.invoke(
                PurchasesError(PurchasesErrorCode.StoreProblemError)
            )
        }

        var purchasesError: PurchasesError? = null

        purchases.getOfferingsWith({ error ->
            purchasesError = error
        }) {
            fail("should be an error")
        }

        assertThat(purchasesError).isNotNull
        verify (exactly = 1) {
            mockCache.clearOfferingsCacheTimestamp()
        }
    }

    @Test
    fun getOfferingsErrorIsCalledIfBadBackendResponse() {
        setup()
        every {
            mockBackend.getOfferings(any(), captureLambda(), any())
        } answers {
            lambda<(JSONObject) -> Unit>().captured.invoke(JSONObject("{}"))
        }
        every {
            mockCache.cachedOfferings
        } returns null

        var purchasesError: PurchasesError? = null

        purchases.getOfferingsWith({ error ->
            purchasesError = error
        }) {
            fail("should be an error")
        }

        assertThat(purchasesError).isNotNull
        assertThat(purchasesError!!.code).isEqualTo(PurchasesErrorCode.UnexpectedBackendResponseError)
        verify (exactly = 1) {
            mockCache.clearOfferingsCacheTimestamp()
        }
    }

    @Test
    fun addAttributionPassesDataToBackend() {
        setup()

        val jsonObject = JSONObject()
        jsonObject.put("key", "value")
        val network = Purchases.AttributionNetwork.APPSFLYER

        val jsonSlot = slot<JSONObject>()
        every {
            mockBackend.postAttributionData(appUserId, network, capture(jsonSlot), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkid"
        mockAdInfo(false, networkUserID)

        Purchases.addAttributionData(jsonObject, network, networkUserID)

        verify { mockBackend.postAttributionData(appUserId, network, any(), any()) }
        assertThat(jsonSlot.captured["key"]).isEqualTo("value")
    }

    @Test
    fun addAttributionConvertsStringStringMapToJsonObject() {
        setup()

        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()
        every {
            mockBackend.postAttributionData(
                appUserId,
                network,
                capture(capturedJSONObject),
                captureLambda()
            )
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(false, networkUserID)

        Purchases.addAttributionData(
            mapOf(
                "adgroup" to null,
                "campaign" to "awesome_campaign",
                "campaign_id" to 1234,
                "iscache" to true
            ),
            network,
            networkUserID
        )

        verify {
            mockBackend.postAttributionData(
                eq(appUserId),
                eq(network),
                any(),
                any()
            )
        }
        assertThat(capturedJSONObject.isCaptured).isTrue()
        assertThat(capturedJSONObject.captured.get("adgroup")).isEqualTo(null)
        assertThat(capturedJSONObject.captured.get("campaign")).isEqualTo("awesome_campaign")
        assertThat(capturedJSONObject.captured.get("campaign_id")).isEqualTo(1234)
        assertThat(capturedJSONObject.captured.get("iscache")).isEqualTo(true)
    }

    @Test
    fun consumesNonSubscriptionPurchasesOn40x() {
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        setup()

        var capturedLambda: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            sku,
            purchaseToken,
            observerMode = false,
            offeringIdentifier = null,
            type = PurchaseType.INAPP
        ) {
            capturedLambda = lambda<PostReceiptDataErrorCallback>().captured
            capturedLambda?.invoke(
                PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                true,
                emptyList()
            )
        }
        mockPostReceiptError(
            skuSub,
            purchaseTokenSub,
            observerMode = false,
            offeringIdentifier = null,
            type = PurchaseType.SUBS
        ) {
            capturedLambda = lambda<PostReceiptDataErrorCallback>().captured
            capturedLambda?.invoke(
                PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                true,
                emptyList()
            )
        }

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.INAPP) +
                getMockedPurchaseList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
        )

        verify (exactly = 1) {
            mockBillingWrapper.consumePurchase(purchaseToken,  any())
        }

        verify (exactly = 0) {
            mockBillingWrapper.consumePurchase(purchaseTokenSub,  any())
        }

        verify (exactly = 1) {
            mockBillingWrapper.acknowledge(purchaseTokenSub,  any())
        }
        assertThat(capturedLambda).isNotNull
    }

    @Test
    fun triesToConsumeNonSubscriptionPurchasesOn50x() {
        setup()

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        var capturedLambda: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            sku,
            purchaseToken,
            observerMode = false,
            offeringIdentifier = null,
            type = PurchaseType.INAPP
        ) {
            capturedLambda = lambda<PostReceiptDataErrorCallback>().captured.also {
                it.invoke(
                    PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                    true,
                    emptyList()
                )
            }
        }
        var capturedLambda1: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            skuSub,
            purchaseTokenSub,
            observerMode = false,
            offeringIdentifier = null,
            type = PurchaseType.SUBS
        ) {
            capturedLambda1 = lambda<PostReceiptDataErrorCallback>().captured.also {
                it.invoke(
                    PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                    true,
                    emptyList()
                )
            }
        }

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.INAPP) +
                getMockedPurchaseList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
        )

        assertThat(capturedLambda).isNotNull
        assertThat(capturedLambda1).isNotNull
        verify (exactly = 1) {
            mockBillingWrapper.consumePurchase(purchaseToken,  any())
        }

        verify (exactly = 0) {
            mockBillingWrapper.consumePurchase(purchaseTokenSub,  any())
        }
    }

    @Test
    fun closeCloses() {
        setup()
        mockCloseActions()

        purchases.close()
        verifyClose()
    }

    @Test
    fun whenNoTokensRestoringPurchasesStillCallListener() {
        setup()

        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<Purchase>) -> Unit>().captured.invoke(emptyList())
        }

        val mockCompletion = mockk<ReceivePurchaserInfoListener>(relaxed = true)
        purchases.restorePurchases(mockCompletion)

        verify {
            mockCompletion.onReceived(any())
        }
    }

    @Test
    fun `given a successful aliasing, success handler is called`() {
        setup()
        every {
            mockIdentityManager.createAlias(
                eq("new_id"),
                captureLambda(),
                any()
            )
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }
        val mockInfo = mockk<PurchaserInfo>()
        every {
            mockBackend.getPurchaserInfo(any(), captureLambda(), any())
        } answers {
            lambda<(PurchaserInfo) -> Unit>().captured.invoke(mockInfo)
        }

        val mockCompletion = mockk<ReceivePurchaserInfoListener>(relaxed = true)
        purchases.createAlias(
            "new_id",
            mockCompletion
        )

        verify {
            mockCompletion.onReceived(mockInfo)
        }
    }

    @Test
    fun `given an unsuccessful aliasing, onError handler is called`() {
        setup()
        val purchasesError = PurchasesError(PurchasesErrorCode.InvalidCredentialsError)
        every {
            mockIdentityManager.createAlias(
                eq("new_id"),
                any(),
                captureLambda()
            )
        } answers {
            lambda<(PurchasesError) -> Unit>().captured.invoke(purchasesError)
        }
        val mockReceivePurchaserInfoListener = mockk<ReceivePurchaserInfoListener>(relaxed = true)
        purchases.createAlias(
            "new_id",
            mockReceivePurchaserInfoListener
        )
        verify {
            mockReceivePurchaserInfoListener.onError(eq(purchasesError))
        }
    }

    @Test
    fun `when creating alias, caches are updated`() {
        setup()
        every {
            mockIdentityManager.createAlias(
                eq("new_id"),
                captureLambda(),
                any()
            )
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }
        val mockInfo = mockk<PurchaserInfo>()
        every {
            mockBackend.getPurchaserInfo(any(), captureLambda(), any())
        } answers {
            lambda<(PurchaserInfo) -> Unit>().captured.invoke(mockInfo)
        }

        val mockCompletion = mockk<ReceivePurchaserInfoListener>(relaxed = true)
        purchases.createAlias(
            "new_id",
            mockCompletion
        )

        verify (exactly = 1) {
            mockCache.setPurchaserInfoCacheTimestampToNow()
        }
        verify (exactly = 1) {
            mockCache.setOfferingsCacheTimestampToNow()
        }
        verify (exactly = 1) {
            mockBackend.getPurchaserInfo("new_id", any(), any())
        }
        verify (exactly = 1) {
            mockBackend.getOfferings("new_id", any(), any())
        }
    }

    @Test
    fun `when identifying, user is identified and caches updated`() {
        setup()

        every {
            mockIdentityManager.identify("new_id", captureLambda(), any())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        purchases.identify("new_id")

        verify (exactly = 1) {
            mockIdentityManager.identify("new_id", any(), any())
        }
        verify (exactly = 1) {
            mockCache.setPurchaserInfoCacheTimestampToNow()
        }
        verify (exactly = 1) {
            mockCache.setOfferingsCacheTimestampToNow()
        }
        verify (exactly = 1) {
            mockBackend.getPurchaserInfo("new_id", any(), any())
        }
        verify (exactly = 1) {
            mockBackend.getOfferings("new_id", any(), any())
        }
    }

    @Test
    fun `when resetting, identity manager is called`() {
        setup()
        every {
            mockCache.clearLatestAttributionData(appUserId)
        } just Runs
        every {
            mockIdentityManager.reset()
        } just Runs
        purchases.reset()
        verify {
            mockIdentityManager.reset()
        }
    }

    @Test
    fun `when resetting, caches are updated`() {
        setup()
        every {
            mockCache.clearLatestAttributionData(appUserId)
        } just Runs
        every {
            mockIdentityManager.reset()
        } just Runs
        purchases.reset()

        verify (exactly = 1) {
            mockCache.setPurchaserInfoCacheTimestampToNow()
        }
        verify (exactly = 1) {
            mockCache.setOfferingsCacheTimestampToNow()
        }
        verify (exactly = 1) {
            mockBackend.getPurchaserInfo("fakeUserID", any(), any())
        }
        verify (exactly = 1) {
            mockBackend.getOfferings("fakeUserID", any(), any())
        }
    }

    @Test
    fun `when setting up, and passing a appUserID, user is identified`() {
        setup()
        assertThat(purchases.allowSharingPlayStoreAccount).isEqualTo(false)
        assertThat(purchases.appUserID).isEqualTo(appUserId)
    }

    @Test
    fun `when setting listener, caches are retrieved`() {
        setup()

        purchases.updatedPurchaserInfoListener = updatedPurchaserInfoListener

        verify {
            updatedPurchaserInfoListener.onReceived(any())
        }
    }

    @Test
    fun `when setting listener, purchases are restored`() {
        setup()
        verify(exactly = 0) {
            mockBillingWrapper.queryPurchaseHistoryAsync(any(), any(), any())
        }
    }

    @Test
    fun `when setting shared instance and there's already an instance, instance is closed`() {
        setup()
        mockCloseActions()
        Purchases.sharedInstance = purchases
        verifyClose()
    }

    @Test
    fun `when setting listener, listener is called`() {
        setup()
        purchases.updatedPurchaserInfoListener = updatedPurchaserInfoListener

        verify(exactly = 1) {
            updatedPurchaserInfoListener.onReceived(any())
        }
    }

    @Test
    fun `when setting listener for anonymous user, listener is called`() {
        setup(anonymous = true)
        purchases.updatedPurchaserInfoListener = updatedPurchaserInfoListener

        verify(exactly = 1) {
            updatedPurchaserInfoListener.onReceived(any())
        }
    }

    @Test
    fun `given a random purchase update, listener is called if purchaser info has changed`() {
        setup()
        val info = mockk<PurchaserInfo>()

        every {
            mockBackend.postReceiptData(
                purchaseToken = any(),
                appUserID = any(),
                isRestore = any(),
                observerMode = any(),
                subscriberAttributes = any(),
                productInfo = any(),
                onSuccess = captureLambda(),
                onError = any()
            )
        } answers {
            lambda<PostReceiptDataSuccessCallback>().captured.invoke(info, emptyList())
        }
        purchases.updatedPurchaserInfoListener = updatedPurchaserInfoListener
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        mockQueryingSkuDetails(sku, PurchaseType.SUBS, null)
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.SUBS)
        )

        verify(exactly = 2) {
            updatedPurchaserInfoListener.onReceived(any())
        }
    }

    @Test
    fun `given a random purchase update, listener is not called if purchaser info has not changed`() {
        setup()
        purchases.updatedPurchaserInfoListener = updatedPurchaserInfoListener
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        mockQueryingSkuDetails(sku, PurchaseType.SUBS, null)
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.SUBS)
        )

        verify(exactly = 1) {
            updatedPurchaserInfoListener.onReceived(any())
        }
    }

    @Test
    fun `when making another purchase for a product for a pending product, error is issued`() {
        setup()
        purchases.updatedPurchaserInfoListener = updatedPurchaserInfoListener

        val skuDetails = mockk<SkuDetails>().also {
            every { it.sku } returns "sku"
        }
        purchases.purchaseProductWith(
            mockk(),
            skuDetails,
            onError = { _, _ -> fail("Should be success") }) { _, _ ->
            // First one works
        }

        var errorCalled: PurchasesError? = null
        purchases.purchaseProductWith(
            mockk(),
            skuDetails,
            onError = { error, _  ->
                errorCalled = error
            }) { _, _ ->
            fail("Should be error")
        }

        assertThat(errorCalled!!.code).isEqualTo(PurchasesErrorCode.OperationAlreadyInProgressError)
    }

    @Test
    fun `when making purchase, completion block is called once`() {
        setup()

        val activity: Activity = mockk()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        mockQueryingSkuDetails(sku, PurchaseType.SUBS, null)

        val skuDetails = mockk<SkuDetails>().also {
            every { it.sku } returns sku
        }

        var callCount = 0
        purchases.purchaseProductWith(
            activity,
            skuDetails,
            onSuccess = { _, _ ->
                callCount++
            }, onError = { _, _ -> fail("should be successful") })

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.SUBS)
        )
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.SUBS)
        )

        assertThat(callCount).isEqualTo(1)
    }

    @Test
    fun `when making purchase, completion block not called for different products`() {
        setup()

        val activity: Activity = mockk()
        val sku = "onemonth_freetrial"
        val sku1 = "onemonth_freetrial_1"
        val purchaseToken1 = "crazy_purchase_token_1"
        var callCount = 0
        mockQueryingSkuDetails(sku1, PurchaseType.SUBS, null)
        purchases.purchaseProductWith(
            activity,
            mockk<SkuDetails>().also {
                every { it.sku } returns sku
            },
            onSuccess = { _, _ ->
                callCount++
            }, onError = { _, _ -> fail("should be successful") })

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku1, purchaseToken1, PurchaseType.SUBS)
        )

        assertThat(callCount).isEqualTo(0)
    }

    @Test
    fun `sends cached purchaser info to getter`() {
        val info = setup()

        every {
            mockBackend.getPurchaserInfo(any(), captureLambda(), any())
        } answers {
            // Timeout
        }

        var receivedInfo: PurchaserInfo? = null
        purchases.getPurchaserInfoWith(onSuccess = {
            receivedInfo = it
        }, onError = {
            fail("supposed to be successful")
        })

        assertThat(receivedInfo).isEqualTo(info)
    }

    @Test
    fun `given no cached purchaser info, backend is called again`() {
        setup()
        every {
            mockCache.getCachedPurchaserInfo(any())
        } returns null
        every {
            mockBackend.getPurchaserInfo(any(), captureLambda(), any())
        } answers {
            // Timeout
        }

        var receivedInfo: PurchaserInfo? = null
        purchases.getPurchaserInfoWith(onSuccess = {
            receivedInfo = it
        }, onError = {
            fail("supposed to be successful")
        })

        assertThat(receivedInfo).isEqualTo(null)
        verify(exactly = 1) { mockBackend.getPurchaserInfo(any(), any(), any()) }
    }

    @Test
    fun `don't create an alias if the new app user id is the same`() {
        setup()
        val lock = CountDownLatch(1)
        purchases.createAliasWith(appUserId) {
            lock.countDown()
        }

        lock.await(200, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isZero()
        verify (exactly = 0) {
            mockBackend.createAlias(appUserId, appUserId, any(), any())
        }
    }

    @Test
    fun `don't identify if the new app user id is the same`() {
        val info = setup()

        var receivedInfo: PurchaserInfo? = null
        purchases.identifyWith(appUserId) {
           receivedInfo = it
        }

        verify (exactly = 1) {
            mockCache.getCachedPurchaserInfo(appUserId)
        }
        assertThat(receivedInfo).isEqualTo(info)
    }

    @Test
    fun `when multiple make purchase callbacks, a failure doesn't throw ConcurrentModificationException`() {
        setup()

        val activity: Activity = mockk()

        purchases.purchaseProductWith(
            activity,
            mockk<SkuDetails>().also {
                every { it.sku } returns "sku"
            }
        ) { _, _ -> }

        purchases.purchaseProductWith(
            activity,
            mockk<SkuDetails>().also {
                every { it.sku } returns "sku"
            }
        ) { _, _ -> }

        try {
            capturedPurchasesUpdatedListener.captured.onPurchasesFailedToUpdate(emptyList(), 0, "fail")
        } catch (e: ConcurrentModificationException) {
            fail("Test throws ConcurrentModificationException")
        }
    }

    @Test
    fun `when checking if Billing is supported, an OK response when starting connection means it's supported`() {
        setup()
        var receivedIsBillingSupported = false
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.enablePendingPurchases()} returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isBillingSupported(mockContext, Callback {
            receivedIsBillingSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponseCode.OK.buildResult())
        AssertionsForClassTypes.assertThat(receivedIsBillingSupported).isTrue()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when checking if Billing is supported, disconnections mean billing is not supported`() {
        setup()
        var receivedIsBillingSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.enablePendingPurchases()} returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isBillingSupported(mockContext, Callback {
            receivedIsBillingSupported = it
        })
        listener.captured.onBillingServiceDisconnected()
        AssertionsForClassTypes.assertThat(receivedIsBillingSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when checking if Billing is supported, a non OK response when starting connection means it's not supported`() {
        setup()
        var receivedIsBillingSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.enablePendingPurchases()} returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isBillingSupported(mockContext, Callback {
            receivedIsBillingSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED.buildResult())
        AssertionsForClassTypes.assertThat(receivedIsBillingSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when checking if feature is supported, an OK response when starting connection means it's supported`() {
        setup()
        var featureSupported = false
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        every {
            mockLocalBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        } returns BillingClient.BillingResponseCode.OK.buildResult()
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.enablePendingPurchases() } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS, mockContext, Callback {
            featureSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponseCode.OK.buildResult())
        AssertionsForClassTypes.assertThat(featureSupported).isTrue()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when checking if feature is supported, disconnections mean billing is not supported`() {
        setup()
        var featureSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.enablePendingPurchases() } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS, mockContext, Callback {
            featureSupported = it
        })
        listener.captured.onBillingServiceDisconnected()
        AssertionsForClassTypes.assertThat(featureSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when checking if feature is supported, a non OK response when starting connection means it's not supported`() {
        setup()
        var featureSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        every {
            mockLocalBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        } returns BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED.buildResult()
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.enablePendingPurchases() } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS, mockContext, Callback {
            featureSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED.buildResult())
        AssertionsForClassTypes.assertThat(featureSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when no play services, feature is not supported`() {
        setup()
        var featureSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        every {
            mockLocalBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        } returns BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED.buildResult()
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.enablePendingPurchases() } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        every { mockLocalBillingClient.endConnection() } throws mockk<IllegalArgumentException>()
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS, mockContext, Callback {
            featureSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED.buildResult())
        AssertionsForClassTypes.assertThat(featureSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when calling isFeatureSupported, enablePendingPurchases is called`() {
        setup()
        var featureSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        every {
            mockLocalBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        } returns BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED.buildResult()
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.enablePendingPurchases() } returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        every { mockLocalBillingClient.endConnection() } throws mockk<IllegalArgumentException>()
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS, mockContext, Callback {
            featureSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED.buildResult())
        AssertionsForClassTypes.assertThat(featureSupported).isFalse()
        verify (exactly = 1) { mockBuilder.enablePendingPurchases() }
    }

    @Test
    fun `when no play services, billing is not supported`() {
        setup()
        var receivedIsBillingSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.enablePendingPurchases()} returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        every { mockLocalBillingClient.endConnection() } throws mockk<IllegalArgumentException>()
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isBillingSupported(mockContext, Callback {
            receivedIsBillingSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED.buildResult())
        AssertionsForClassTypes.assertThat(receivedIsBillingSupported).isFalse()
        verify (exactly = 1) { mockLocalBillingClient.endConnection() }
    }

    @Test
    fun `when calling isBillingSupported, enablePendingPurchases is called`() {
        setup()
        var receivedIsBillingSupported = true
        val mockLocalBillingClient = mockk<BillingClient>(relaxed = true)
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        every { mockBuilder.enablePendingPurchases()} returns mockBuilder
        every { mockBuilder.build() } returns mockLocalBillingClient
        every { mockLocalBillingClient.endConnection() } throws mockk<IllegalArgumentException>()
        val listener = slot<BillingClientStateListener>()
        every { mockLocalBillingClient.startConnection(capture(listener)) } just Runs
        Purchases.isBillingSupported(mockContext, Callback {
            receivedIsBillingSupported = it
        })
        listener.captured.onBillingSetupFinished(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED.buildResult())
        AssertionsForClassTypes.assertThat(receivedIsBillingSupported).isFalse()
        verify (exactly = 1) { mockBuilder.enablePendingPurchases() }
    }

    @Test
    fun `when finishTransactions is set to false, do not consume transactions but save token in cache`() {
        val mockInfo = setup()
        purchases.finishTransactions = false
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "onemonth_freetrial_sub"
        val purchaseTokenSub = "crazy_purchase_token_sub"

        val productInfo = mockPostReceipt(
            sku,
            purchaseToken,
            observerMode = true,
            mockInfo = mockInfo,
            offeringIdentifier = null,
            type = PurchaseType.INAPP
        )

        val productInfo1 = mockPostReceipt(
            skuSub,
            purchaseTokenSub,
            observerMode = true,
            mockInfo = mockInfo,
            offeringIdentifier = null,
            type = PurchaseType.SUBS
        )

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.INAPP) +
            getMockedPurchaseList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
        )

        verify(exactly = 1){
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = false,
                observerMode = true,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }

        verify(exactly = 1){
            mockBackend.postReceiptData(
                purchaseToken = purchaseTokenSub,
                appUserID = appUserId,
                isRestore = false,
                observerMode = true,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo1,
                onSuccess = any(),
                onError = any()
            )
        }
        verify(exactly = 0) {
            mockBillingWrapper.consumePurchase(purchaseToken, any())
        }
        verify(exactly = 0) {
            mockBillingWrapper.consumePurchase(purchaseTokenSub, any())
        }
        verify(exactly = 1) {
            mockCache.addSuccessfullyPostedToken(purchaseToken)
        }
        verify(exactly = 1) {
            mockCache.addSuccessfullyPostedToken(purchaseTokenSub)
        }
    }

    @Test
    fun `when finishTransactions is set to false, don't consume transactions on 40x but save token in cache`() {
        setup()

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "onemonth_freetrial_sub"
        val purchaseTokenSub = "crazy_purchase_token_sub"

        var capturedLambda: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            sku,
            purchaseToken,
            observerMode = true,
            offeringIdentifier = null,
            type = PurchaseType.INAPP
        ) {
            capturedLambda = lambda<PostReceiptDataErrorCallback>().captured.also {
                it.invoke(
                    PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                    true,
                    emptyList()
                )
            }
        }

        var capturedLambda1: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            skuSub,
            purchaseTokenSub,
            observerMode = true,
            offeringIdentifier = null,
            type = PurchaseType.SUBS
        ) {
            capturedLambda1 = lambda<PostReceiptDataErrorCallback>().captured.also {
                it.invoke(
                    PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                    true,
                    emptyList()
                )
            }
        }

        purchases.finishTransactions = false

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.INAPP) +
                getMockedPurchaseList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
        )

        assertThat(capturedLambda).isNotNull
        assertThat(capturedLambda1).isNotNull
        verify (exactly = 0) {
            mockBillingWrapper.consumePurchase(purchaseToken, any())
        }
        verify (exactly = 0) {
            mockBillingWrapper.consumePurchase(purchaseTokenSub, any())
        }
        verify(exactly = 1) {
            mockCache.addSuccessfullyPostedToken(purchaseToken)
        }
        verify(exactly = 1) {
            mockCache.addSuccessfullyPostedToken(purchaseTokenSub)
        }
    }

    @Test
    fun `when finishTransactions is set to false, don't consume transactions on 50x but save token in cache`() {
        setup()

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "onemonth_freetrial_sub"
        val purchaseTokenSub = "crazy_purchase_token_sub"

        var captured: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            sku,
            purchaseToken,
            observerMode = true,
            offeringIdentifier = null,
            type = PurchaseType.INAPP,
            answer = {
                captured = lambda<PostReceiptDataErrorCallback>().captured.also {
                    it.invoke(PurchasesError(PurchasesErrorCode.InvalidCredentialsError), true, emptyList())
                }
            })
        mockPostReceiptError(
            skuSub,
            purchaseTokenSub,
            observerMode = true,
            offeringIdentifier = null,
            type = PurchaseType.SUBS,
            answer = {
                captured = lambda<PostReceiptDataErrorCallback>().captured.also {
                    it.invoke(PurchasesError(PurchasesErrorCode.InvalidCredentialsError), true, emptyList())
                }
            })
        purchases.finishTransactions = false

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.INAPP) +
            getMockedPurchaseList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
        )

        assertThat(captured).isNotNull
        verify (exactly = 0) {
            mockBillingWrapper.consumePurchase(purchaseToken, any())
        }
        verify (exactly = 0) {
            mockBillingWrapper.consumePurchase(purchaseTokenSub, any())
        }
        verify(exactly = 1) {
            mockCache.addSuccessfullyPostedToken(purchaseToken)
        }
        verify(exactly = 1) {
            mockCache.addSuccessfullyPostedToken(purchaseTokenSub)
        }
    }

    @Test
    fun `syncing transactions gets whole history and posts it to backend`() {
        setup()
        purchases.finishTransactions = false

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "onemonth_freetrial_sub"
        val purchaseTokenSub = "crazy_purchase_token_sub"

        var capturedLambda: ((List<PurchaseHistoryRecordWrapper>) -> Unit)? = null
        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            capturedLambda = lambda<(List<PurchaseHistoryRecordWrapper>) -> Unit>().captured.also {
                it.invoke(
                    getMockedPurchaseHistoryList(sku, purchaseToken, PurchaseType.INAPP) +
                    getMockedPurchaseHistoryList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
                )
            }
        }

        purchases.syncPurchases()

        val productInfo = ProductInfo(
            productID = sku
        )
        assertThat(capturedLambda).isNotNull
        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = false,
                observerMode = true,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
        val productInfo1 = ProductInfo(
            productID = skuSub
        )
        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseTokenSub,
                appUserID = appUserId,
                isRestore = false,
                observerMode = true,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo1,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun `syncing transactions respects allow sharing account settings`() {
        setup()
        purchases.finishTransactions = false

        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "onemonth_freetrial_sub"
        val purchaseTokenSub = "crazy_purchase_token_sub"
        purchases.allowSharingPlayStoreAccount = true

        var capturedLambda: ((List<PurchaseHistoryRecordWrapper>) -> Unit)? = null
        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            capturedLambda = lambda<(List<PurchaseHistoryRecordWrapper>) -> Unit>().captured.also {
                it.invoke(
                    getMockedPurchaseHistoryList(sku, purchaseToken, PurchaseType.INAPP) +
                        getMockedPurchaseHistoryList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
                )
            }
        }

        purchases.syncPurchases()

        val productInfo = ProductInfo(
            productID = sku
        )
        assertThat(capturedLambda).isNotNull
        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = true,
                observerMode = true,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }

        val productInfo1 = ProductInfo(
            productID = skuSub
        )
        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseTokenSub,
                appUserID = appUserId,
                isRestore = true,
                observerMode = true,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo1,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun `syncing transactions never consumes transactions`() {
        setup()
        purchases.finishTransactions = false
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        purchases.allowSharingPlayStoreAccount = true

        var capturedLambda: ((List<PurchaseHistoryRecordWrapper>) -> Unit)? = null
        every {
            mockBillingWrapper.queryAllPurchases(
                captureLambda(),
                any()
            )
        } answers {
            capturedLambda = lambda<(List<PurchaseHistoryRecordWrapper>) -> Unit>().captured.also {
                it.invoke(getMockedPurchaseHistoryList(sku, purchaseToken, PurchaseType.INAPP))
            }
        }

        purchases.syncPurchases()

        val productInfo = ProductInfo(
            productID = sku
        )
        verify {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = true,
                observerMode = true,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
        verify (exactly = 0){
            mockBillingWrapper.consumePurchase(eq(purchaseToken), any())
        }
        assertThat(capturedLambda).isNotNull
    }

    @Test
    fun `Data is successfully postponed if no instance is set`() {
        val jsonObject = JSONObject()
        val network = Purchases.AttributionNetwork.APPSFLYER

        every {
            mockBackend.postAttributionData(appUserId, network, jsonObject, captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        Purchases.addAttributionData(jsonObject, network, networkUserID)

        mockAdInfo(false, networkUserID)

        setup()

        verify { mockBackend.postAttributionData(eq(appUserId), eq(network), eq(jsonObject), any()) }
    }

    @Test
    fun `Data is successfully postponed if no instance is set when sending map`() {
        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(false, networkUserID)
        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        setup()

        verify {
            mockBackend.postAttributionData(eq(appUserId), eq(network), any(), any())
        }
        assertThat(capturedJSONObject.captured.get("key")).isEqualTo("value")
    }

    @Test
    fun `GPS ID is automatically added`() {
        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(false, networkUserID)

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        setup()

        verify {
            mockBackend.postAttributionData(
                eq(appUserId),
                eq(network),
                any(),
                any()
            )
        }
        assertThat(capturedJSONObject.captured.get("key")).isEqualTo("value")
        assertThat(capturedJSONObject.captured.get("rc_gps_adid")).isEqualTo(adID)
    }

    @Test
    fun `GPS ID is not added if limited`() {
        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(true, networkUserID)

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        setup()

        verify {
            mockBackend.postAttributionData(eq(appUserId), eq(network), any(), any())
        }
        assertThat(capturedJSONObject.captured.get("key")).isEqualTo("value")
        assertThat(capturedJSONObject.captured.has("rc_gps_adid")).isFalse()
    }

    @Test
    fun `GPS ID is not added if not present`() {
        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(true, networkUserID)

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        setup()

        verify {
            mockBackend.postAttributionData(
                eq(appUserId),
                eq(network),
                any(),
                any()
            )
        }
        assertThat(capturedJSONObject.captured.get("key")).isEqualTo("value")
        assertThat(capturedJSONObject.captured.has("rc_gps_adid")).isFalse()
    }

    @Test
    fun `do not resend last attribution data to backend`() {
        setup()

        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(false, networkUserID)

        every {
            mockCache.getCachedAttributionData(Purchases.AttributionNetwork.APPSFLYER, appUserId)
        } returns "${adID}_networkUserID"

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        verify (exactly = 0){
            mockBackend.postAttributionData(appUserId, network, any(), any())
        }
    }

    @Test
    fun `cache last sent attribution data`() {
        setup()

        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkid"
        mockAdInfo(false, networkUserID)

        every {
            mockCache.getCachedAttributionData(Purchases.AttributionNetwork.APPSFLYER, appUserId)
        } returns null

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        verify (exactly = 1){
            mockBackend.postAttributionData(appUserId, network, any(), any())
        }

        verify (exactly = 1){
            mockCache.cacheAttributionData(network, appUserId, "${adID}_$networkUserID")
        }
    }

    @Test
    fun `network ID is set`() {
        val network = Purchases.AttributionNetwork.APPSFLYER
        val capturedJSONObject = slot<JSONObject>()

        every {
            mockBackend.postAttributionData(appUserId, network, capture(capturedJSONObject), captureLambda())
        } answers {
            lambda<() -> Unit>().captured.invoke()
        }

        val networkUserID = "networkUserID"
        mockAdInfo(false, networkUserID)

        Purchases.addAttributionData(mapOf("key" to "value"), network, networkUserID)

        setup()

        verify {
            mockBackend.postAttributionData(eq(appUserId), eq(network), any(), any())
        }
        assertThat(capturedJSONObject.captured.get("key")).isEqualTo("value")
        assertThat(capturedJSONObject.captured.get("rc_attribution_network_id")).isEqualTo(networkUserID)
        assertThat(capturedJSONObject.captured.has("rc_gps_adid")).isTrue()
    }

    @Test
    fun `caches are not cleared if update purchaser info fails`() {
        setup(PurchasesError(PurchasesErrorCode.StoreProblemError, "Broken"))
        val lock = CountDownLatch(1)
        purchases.getPurchaserInfoWith(onSuccess = {
            lock.countDown()
        }, onError = {
            fail("supposed to be successful")
        })
        lock.await(200, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isZero()
        verify (exactly = 0) { mockCache.clearCachesForAppUserID(appUserId) }
    }

    @Test
    fun `successfully posted receipts are not saved in cache if consumption fails`() {
        val mockInfo = setup()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        mockPostReceipt(
            sku,
            purchaseToken,
            observerMode = false,
            mockInfo = mockInfo,
            offeringIdentifier = null,
            type = PurchaseType.INAPP
        )
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            sku,
            purchaseToken,
            PurchaseType.INAPP,
            null
        ))
        capturedConsumeResponseListener.captured.invoke(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE.buildResult(), purchaseToken)
        verify (exactly = 0) {
            mockCache.addSuccessfullyPostedToken(purchaseToken)
        }
    }

    @Test
    fun `when error posting receipts, tokens are not saved in cache if error is finishable and consumption fails`() {
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        setup()

        var capturedLambda: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            sku,
            purchaseToken,
            observerMode = false,
            offeringIdentifier = null,
            type = PurchaseType.INAPP,
            answer = {
                capturedLambda = lambda<PostReceiptDataErrorCallback>().captured
            })

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            sku,
            purchaseToken,
            PurchaseType.INAPP,
            null
        ))
        assertThat(capturedLambda).isNotNull
        capturedLambda!!.invoke(
            PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
            true,
            emptyList()
        )
        capturedConsumeResponseListener.captured.invoke(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE.buildResult(), purchaseToken)
        verify (exactly = 0 ) {
            mockCache.addSuccessfullyPostedToken(purchaseToken)
        }
    }

    @Test
    fun `successfully posted receipts are saved in cache after consumption`() {
        val mockInfo = setup()
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        mockPostReceipt(
            sku,
            purchaseToken,
            observerMode = false,
            mockInfo = mockInfo,
            offeringIdentifier = null,
            type = PurchaseType.INAPP
        )
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            sku,
            purchaseToken,
            PurchaseType.INAPP,
            null
        ))
        capturedConsumeResponseListener.captured.invoke(BillingClient.BillingResponseCode.OK.buildResult(), purchaseToken)
        verify (exactly = 1) {
            mockCache.addSuccessfullyPostedToken(purchaseToken)
        }
    }

    @Test
    fun `when error posting receipts tokens are saved in cache if error is finishable`() {
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"

        setup()

        var capturedLambda: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            sku,
            purchaseToken,
            observerMode = false,
            offeringIdentifier = null,
            type = PurchaseType.INAPP,
            answer = {
                capturedLambda = lambda<PostReceiptDataErrorCallback>().captured
            })

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            sku,
            purchaseToken,
            PurchaseType.INAPP,
            null
        ))
        assertThat(capturedLambda).isNotNull
        capturedLambda!!.invoke(
            PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
            true,
            emptyList()
        )
        capturedConsumeResponseListener.captured.invoke(BillingClient.BillingResponseCode.OK.buildResult(), "crazy_purchase_token")
        verify (exactly = 1) {
            mockCache.addSuccessfullyPostedToken("crazy_purchase_token")
        }
        verify (exactly = 1) {
            mockBillingWrapper.consumePurchase("crazy_purchase_token", any())
        }
    }

    @Test
    fun `when error posting receipts tokens are not saved in cache if error is not finishable`() {
        val sku = "onemonth_freetrial"
        val purchaseToken = "crazy_purchase_token"
        val skuSub = "onemonth_freetrial_sub"
        val purchaseTokenSub = "crazy_purchase_token_sub"

        setup()

        var capturedLambda: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            sku,
            purchaseToken,
            observerMode = false,
            offeringIdentifier = null,
            type = PurchaseType.INAPP
        ) {
            capturedLambda = lambda<PostReceiptDataErrorCallback>().captured.also {
                it.invoke(PurchasesError(PurchasesErrorCode.InvalidCredentialsError), false, emptyList())
            }
        }

        var capturedLambda1: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            skuSub,
            purchaseTokenSub,
            observerMode = false,
            offeringIdentifier = null,
            type = PurchaseType.SUBS
        ) {
            capturedLambda1 = lambda<PostReceiptDataErrorCallback>().captured.also {
                it.invoke(PurchasesError(PurchasesErrorCode.InvalidCredentialsError), false, emptyList())
            }
        }

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.INAPP) +
                getMockedPurchaseList(skuSub, purchaseTokenSub, PurchaseType.SUBS)
        )

        assertThat(capturedLambda).isNotNull
        assertThat(capturedLambda1).isNotNull

        verify (exactly = 0) {
            mockCache.addSuccessfullyPostedToken(purchaseToken)
        }
        verify (exactly = 0) {
            mockCache.addSuccessfullyPostedToken(purchaseTokenSub)
        }
    }

    @Test
    fun `reposted receipts are sent using allowSharingAccount`() {
        setup()
        purchases.allowSharingPlayStoreAccount = true
        val purchase = mockk<Purchase>(relaxed = true).apply {
            every { purchaseToken } returns "token"
            every { sku } returns "product"
            every { purchaseState } returns Purchase.PurchaseState.PURCHASED
        }
        val activePurchase = PurchaseWrapper(purchase, PurchaseType.SUBS, null)
        mockSuccessfulQueryPurchases(
            queriedSUBS = mapOf(purchase.purchaseToken.sha1() to activePurchase),
            queriedINAPP = emptyMap(),
            notInCache = listOf(activePurchase)
        )
        val productInfo = mockQueryingSkuDetails("product", PurchaseType.SUBS, null)

        purchases.updatePendingPurchaseQueue()

        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = "token",
                appUserID = appUserId,
                isRestore = true,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun `when closing instance, activity lifecycle callbacks are unregistered`() {
        setup()

        every {
            ProcessLifecycleOwner.get()
        } returns mockLifecycleOwner
        every {
            mockLifecycleOwner.lifecycle
        } returns mockLifecycle
        every {
            mockLifecycle.removeObserver(any())
        } just Runs
        purchases.close()
        verify (exactly = 1) {
            mockLifecycle.removeObserver(any())
        }
    }

    @Test
    fun `when updating pending purchases, retrieve both INAPPS and SUBS`() {
        setup()
        mockSuccessfulQueryPurchases(
            queriedSUBS = emptyMap(),
            queriedINAPP = emptyMap(),
            notInCache = emptyList()
        )
        purchases.updatePendingPurchaseQueue()
        verify (exactly = 1) {
            mockBillingWrapper.queryPurchases(PurchaseType.SUBS.toSKUType()!!)
        }
        verify (exactly = 1) {
            mockBillingWrapper.queryPurchases(PurchaseType.INAPP.toSKUType()!!)
        }
    }

    @Test
    fun `when updating pending purchases, if token has not been sent, send it`() {
        setup()
        val purchase = mockk<Purchase>(relaxed = true).apply {
            every { purchaseToken } returns "token"
            every { sku } returns "product"
            every { purchaseState } returns Purchase.PurchaseState.PURCHASED
        }
        val newPurchase = PurchaseWrapper(purchase, PurchaseType.SUBS, null)
        mockSuccessfulQueryPurchases(
            queriedSUBS = mapOf(purchase.purchaseToken.sha1() to newPurchase),
            queriedINAPP = emptyMap(),
            notInCache = listOf(newPurchase)
        )
        val productInfo = mockQueryingSkuDetails("product", PurchaseType.SUBS, null)

        purchases.updatePendingPurchaseQueue()

        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = "token",
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun `when updating pending purchases, if token has been sent, don't send it`() {
        setup()
        val token = "1234token"
        val purchase = mockk<Purchase>(relaxed = true).apply {
            every { purchaseToken } returns token
            every { sku } returns "product"
        }
        mockSuccessfulQueryPurchases(
            queriedSUBS = mapOf(purchase.purchaseToken.sha1() to PurchaseWrapper(
                purchase,
                PurchaseType.SUBS,
                null
            )),
            queriedINAPP = emptyMap(),
            notInCache = emptyList()
        )
        purchases.updatePendingPurchaseQueue()

        val productInfo = ProductInfo(
            productID = "product"
        )
        verify (exactly = 0) {
            mockBackend.postReceiptData(
                purchaseToken = token,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun `when updating pending purchases, if result from query SUBS is not positive skip`() {
        setup()
        every {
            mockExecutorService.isShutdown
        } returns false
        every {
            mockBillingWrapper.queryPurchases(PurchaseType.SUBS.toSKUType()!!)
        } returns BillingWrapper.QueryPurchasesResult(-1, emptyMap())
        every {
            mockBillingWrapper.queryPurchases(PurchaseType.INAPP.toSKUType()!!)
        } returns BillingWrapper.QueryPurchasesResult(0, emptyMap())
        purchases.updatePendingPurchaseQueue()
        verify (exactly = 0) {
            mockCache.getPreviouslySentHashedTokens()
        }
    }

    @Test
    fun `when updating pending purchases, if result from query INAPP is not positive skip`() {
        setup()
        every {
            mockExecutorService.isShutdown
        } returns false
        every {
            mockBillingWrapper.queryPurchases(PurchaseType.SUBS.toSKUType()!!)
        } returns BillingWrapper.QueryPurchasesResult(0, emptyMap())
        every {
            mockBillingWrapper.queryPurchases(PurchaseType.INAPP.toSKUType()!!)
        } returns BillingWrapper.QueryPurchasesResult(-1, emptyMap())
        purchases.updatePendingPurchaseQueue()
        verify (exactly = 0) {
            mockCache.getPreviouslySentHashedTokens()
        }
    }

    @Test
    fun `on billing wrapper connected, query purchases`() {
        setup()
        mockSuccessfulQueryPurchases(
            queriedSUBS = emptyMap(),
            queriedINAPP = emptyMap(),
            notInCache = emptyList()
        )
        capturedBillingWrapperStateListener.captured.onConnected()
        verify (exactly = 1) {
            mockBillingWrapper.queryPurchases(PurchaseType.SUBS.toSKUType()!!)
        }
        verify (exactly = 1) {
            mockBillingWrapper.queryPurchases(PurchaseType.INAPP.toSKUType()!!)
        }
    }

    @Test
    fun `on app foregrounded query purchases`() {
        setup()
        mockSuccessfulQueryPurchases(
            queriedSUBS = emptyMap(),
            queriedINAPP = emptyMap(),
            notInCache = emptyList()
        )
        mockSynchronizeSubscriberAttributesForAllUsers()
        purchases.onAppForegrounded()
        verify (exactly = 1) {
            mockBillingWrapper.queryPurchases(PurchaseType.SUBS.toSKUType()!!)
        }
        verify (exactly = 1) {
            mockBillingWrapper.queryPurchases(PurchaseType.INAPP.toSKUType()!!)
        }
    }

    @Test
    fun `if billing client not connected do not query purchases`() {
        setup()
        every {
            mockBillingWrapper.isConnected()
        } returns false
        purchases.updatePendingPurchaseQueue()
        verify (exactly = 0) {
            mockBillingWrapper.queryPurchases(PurchaseType.SUBS.toSKUType()!!)
        }
        verify (exactly = 0) {
            mockBillingWrapper.queryPurchases(PurchaseType.INAPP.toSKUType()!!)
        }
    }

    @Test
    fun `when updating pending purchases, do not do anything if executor service disconnected`() {
        setup()
        every {
            mockExecutorService.isShutdown
        } returns true
        purchases.updatePendingPurchaseQueue()
        verify (exactly = 0) {
            mockBillingWrapper.queryPurchases(any())
        }
    }

    @Test
    fun `Do not post or consume pending purchases`() {
        setup()
        val sku = "inapp"
        val purchaseToken = "token_inapp"
        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(sku, purchaseToken, PurchaseType.INAPP, purchaseState = Purchase.PurchaseState.PENDING) +
                getMockedPurchaseList(skuSub, purchaseTokenSub, PurchaseType.SUBS, "offering_a", purchaseState = Purchase.PurchaseState.PENDING)
        )

        val productInfo = ProductInfo(
            productID = sku
        )
        verify (exactly = 0) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }

        val productInfo1 = ProductInfo(
            productID = skuSub,
            offeringIdentifier = "offering_a"
        )
        verify (exactly = 0) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseTokenSub,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo1,
                onSuccess = any(),
                onError = any()
            )
        }

        verify (exactly = 0) {
            mockBillingWrapper.consumePurchase(any(), any())
        }

        verify (exactly = 0) {
            mockBillingWrapper.acknowledge(any(), any())
        }

        verify (exactly = 0) {
            mockCache.addSuccessfullyPostedToken(any())
        }
    }

    @Test
    fun `Do not acknowledge purchases that are already acknowledged`() {
        val mockInfo = setup()
        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        val productInfo = mockPostReceipt(
            sku = skuSub,
            purchaseToken = purchaseTokenSub,
            observerMode = false,
            mockInfo = mockInfo,
            offeringIdentifier = "offering_a",
            type = PurchaseType.SUBS
        )

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(skuSub, purchaseTokenSub, PurchaseType.SUBS, "offering_a", acknowledged = true)
        )

        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseTokenSub,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }

        verify (exactly = 0) {
            mockBillingWrapper.acknowledge(any(), any())
        }

        verify (exactly = 1) {
            mockCache.addSuccessfullyPostedToken(any())
        }
    }

    @Test
    fun `Acknowledge subscriptions`() {
        setup()
        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        val productInfo = mockQueryingSkuDetails(skuSub, PurchaseType.SUBS, "offering_a")

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(
            getMockedPurchaseList(
                skuSub,
                purchaseTokenSub,
                PurchaseType.SUBS,
                "offering_a",
                acknowledged = false
            )
        )

        capturedAcknowledgeResponseListener.captured.invoke(BillingClient.BillingResponseCode.OK.buildResult(), purchaseTokenSub)

        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseTokenSub,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }

        verify (exactly = 1) {
            mockBillingWrapper.acknowledge(any(), any())
        }

        verify (exactly = 1) {
            mockCache.addSuccessfullyPostedToken(any())
        }
    }

    @Test
    fun `successfully posted receipts are not save in cache if acknowledge fails`() {
        setup()
        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        mockQueryingSkuDetails(skuSub, PurchaseType.SUBS, null)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            skuSub,
            purchaseTokenSub,
            PurchaseType.SUBS,
            "offering_a"
        ))
        capturedAcknowledgeResponseListener.captured.invoke(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE.buildResult(),
            purchaseTokenSub
        )
        verify (exactly = 0) {
            mockCache.addSuccessfullyPostedToken(purchaseTokenSub)
        }
    }

    @Test
    fun `when error posting subscription, tokens are not saved in cache if error is finishable and acknowledgement fails`() {
        setup()

        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        var capturedLambda: (PostReceiptDataErrorCallback)? = null

        mockPostReceiptError(
            skuSub,
            purchaseTokenSub,
            observerMode = false,
            offeringIdentifier = "offering_a",
            type = PurchaseType.SUBS
        ) {
            capturedLambda = lambda<PostReceiptDataErrorCallback>().captured.also {
                it.invoke(
                    PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                    true,
                    emptyList()
                )
            }
        }

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            skuSub,
            purchaseTokenSub,
            PurchaseType.SUBS,
            "offering_a"
        ))
        capturedAcknowledgeResponseListener.captured.invoke(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE.buildResult(),
            purchaseTokenSub
        )
        assertThat(capturedLambda).isNotNull
        verify (exactly = 0 ) {
            mockCache.addSuccessfullyPostedToken(purchaseTokenSub)
        }
    }

    @Test
    fun `when error posting subscription receipts, tokens are saved in cache if error is finishable`() {
        setup()

        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        var capturedLambda: (PostReceiptDataErrorCallback)? = null
        mockPostReceiptError(
            skuSub,
            purchaseTokenSub,
            observerMode = false,
            offeringIdentifier = null,
            type = PurchaseType.SUBS
        ) {
            capturedLambda = lambda<PostReceiptDataErrorCallback>().captured
            capturedLambda?.invoke(
                PurchasesError(PurchasesErrorCode.InvalidCredentialsError),
                true,
                emptyList()
            )
        }
        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            skuSub,
            purchaseTokenSub,
            PurchaseType.SUBS,
            null
        ))
        capturedAcknowledgeResponseListener.captured.invoke(
            BillingClient.BillingResponseCode.OK.buildResult(),
            purchaseTokenSub
        )
        verify (exactly = 1) {
            mockCache.addSuccessfullyPostedToken(purchaseTokenSub)
        }
    }

    @Test
    fun `posted inapps also post currency and price`() {
        setup()
        val sku = "sku"
        val purchaseToken = "token"

        val productInfo = mockQueryingSkuDetails(sku, PurchaseType.INAPP, offeringIdentifier = "offering_a")

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            sku,
            purchaseToken,
            PurchaseType.INAPP,
            "offering_a"
        ))

        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun `posted subs post currency and price`() {
        setup()
        val skuSub = "sub"
        val purchaseTokenSub = "token_sub"

        mockSkuDetails(listOf(skuSub), emptyList(), PurchaseType.SUBS)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            skuSub,
            purchaseTokenSub,
            PurchaseType.SUBS,
            "offering_a"
        ))
        val productInfo = ProductInfo(
            productID = skuSub,
            offeringIdentifier = "offering_a"
        )
        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseTokenSub,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }

        verify (exactly = 1) {
            mockBillingWrapper.querySkuDetailsAsync(
                BillingClient.SkuType.SUBS,
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `invalidate purchaser info caches`() {
        setup()
        Purchases.sharedInstance.invalidatePurchaserInfoCache()
        verify (exactly = 1) {
            mockCache.clearPurchaserInfoCacheTimestamp()
        }
    }

    @Test
    fun `error when fetching purchaser info`() {
        setup(PurchasesError(PurchasesErrorCode.StoreProblemError, "Broken"))
        every {
            mockCache.getCachedPurchaserInfo(any())
        } returns null
        val lock = CountDownLatch(1)
        purchases.getPurchaserInfoWith(onSuccess = {
            fail("supposed to be error")
        }, onError = {
            lock.countDown()
        })
        lock.await(200, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isZero()
        verify (exactly = 1) { mockCache.clearPurchaserInfoCacheTimestamp() }
    }

    @Test
    fun `product not found when querying sku details while purchasing`() {
        setup()
        val sku = "sku"
        val purchaseToken = "token"

        mockSkuDetails(listOf(sku), emptyList(), PurchaseType.INAPP)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            sku,
            purchaseToken,
            PurchaseType.INAPP,
            "offering_a"
        ))
        val productInfo = ProductInfo(
            productID = sku,
            offeringIdentifier = "offering_a"
        )
        verify (exactly = 1) {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = false,
                observerMode = false,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = any()
            )
        }
    }

    @Test
    fun `Setting platform info sets it in the AppConfig when configuring the SDK`() {
        val expected = PlatformInfo("flavor", "version")
        Purchases.platformInfo = expected
        Purchases.configure(mockContext, "api")
        assertThat(Purchases.sharedInstance.appConfig.platformInfo).isEqualTo(expected)
    }

    @Test
    fun `Setting proxy URL info sets it in the HttpClient when configuring the SDK`() {
        val expected = URL("https://a-proxy.com")
        Purchases.proxyURL = expected
        Purchases.configure(mockContext, "api")
        assertThat(Purchases.sharedInstance.appConfig.baseURL).isEqualTo(expected)
    }

    @Test
    fun `Setting observer mode on sets finish transactions to false`() {
        Purchases.configure(mockContext, "api", observerMode = true)
        assertThat(Purchases.sharedInstance.appConfig.finishTransactions).isFalse()
    }

    @Test
    fun `Setting observer mode off sets finish transactions to true`() {
        Purchases.configure(mockContext, "api", observerMode = false)
        assertThat(Purchases.sharedInstance.appConfig.finishTransactions).isTrue()
    }

    @Test
    fun `Send error if cannot find the old purchase associated when upgrading a SKU`() {
        setup()

        val activity: Activity = mockk()
        val (skuDetails, offerings) = stubOfferings("onemonth_freetrial")

        val oldPurchase = mockk<PurchaseHistoryRecordWrapper>()
        every { oldPurchase.sku } returns "oldSku"
        every { oldPurchase.type } returns PurchaseType.SUBS

        every {
            mockBillingWrapper.findPurchaseInPurchaseHistory(PurchaseType.SUBS.toSKUType()!!, "oldSku", captureLambda())
        } answers {
            lambda<(BillingResult, PurchaseHistoryRecordWrapper?) -> Unit>().captured.invoke(BillingResult(), null)
        }

        var receivedError: PurchasesError? = null
        var receivedUserCancelled: Boolean? = null
        purchases.purchasePackageWith(
            activity,
            offerings[stubOfferingIdentifier]!!.monthly!!,
            UpgradeInfo(oldPurchase.sku),
            onError = { error, userCancelled ->
                receivedError = error
                receivedUserCancelled = userCancelled
            },
            onSuccess =  { _, _ -> }
        )

        verify (exactly = 0) {
            mockBillingWrapper.makePurchaseAsync(
                eq(activity),
                eq(appUserId),
                skuDetails,
                ReplaceSkuInfo(oldPurchase),
                stubOfferingIdentifier
            )
        }
        assertThat(receivedError).isNotNull
        assertThat(receivedError!!.code).isEqualTo(PurchasesErrorCode.PurchaseInvalidError)
        assertThat(receivedUserCancelled).isFalse()
    }

    @Test
    fun `Send error if cannot find the old purchase associated when upgrading a SKU due to a billingclient error`() {
        setup()

        val activity: Activity = mockk()
        val (skuDetails, offerings) = stubOfferings("onemonth_freetrial")

        val oldPurchase = mockk<PurchaseHistoryRecordWrapper>()
        every { oldPurchase.sku } returns "oldSku"
        every { oldPurchase.type } returns PurchaseType.SUBS

        val stubBillingResult = mockk<BillingResult>()
        every { stubBillingResult.responseCode } returns BillingClient.BillingResponseCode.ERROR
        every {
            mockBillingWrapper.findPurchaseInPurchaseHistory(PurchaseType.SUBS.toSKUType()!!, "oldSku", captureLambda())
        } answers {
            lambda<(BillingResult, PurchaseHistoryRecordWrapper?) -> Unit>().captured.invoke(stubBillingResult, null)
        }

        var receivedError: PurchasesError? = null
        var receivedUserCancelled: Boolean? = null
        purchases.purchasePackageWith(
            activity,
            offerings[stubOfferingIdentifier]!!.monthly!!,
            UpgradeInfo(oldPurchase.sku),
            onError = { error, userCancelled ->
                receivedError = error
                receivedUserCancelled = userCancelled
            },
            onSuccess =  { _, _ -> }
        )

        verify (exactly = 0) {
            mockBillingWrapper.makePurchaseAsync(
                eq(activity),
                eq(appUserId),
                skuDetails,
                ReplaceSkuInfo(oldPurchase),
                stubOfferingIdentifier
            )
        }
        assertThat(receivedError).isNotNull
        assertThat(receivedError!!.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
        assertThat(receivedUserCancelled).isFalse()
    }

    // region Private Methods
    private fun mockBillingWrapper() {
        with(mockBillingWrapper) {
            every {
                makePurchaseAsync(any(), any(), any(), any(), any())
            } just Runs
            every {
                purchasesUpdatedListener = capture(capturedPurchasesUpdatedListener)
            } just Runs
            every {
                consumePurchase(any(), capture(capturedConsumeResponseListener))
            } just Runs
            every {
                acknowledge(any(), capture(capturedAcknowledgeResponseListener))
            } just Runs
            every {
                acknowledge(any(), capture(capturedAcknowledgeResponseListener))
            } just Runs
            every {
                purchasesUpdatedListener = null
            } just Runs
            every {
                stateListener = capture(capturedBillingWrapperStateListener)
            } just Runs
            every {
                isConnected()
            } returns true
        }
    }

    @Test
    fun `unknown product type when querying sku details while purchasing defaults to inapp`() {
        setup()
        val sku = "sku"
        val purchaseToken = "token"

        mockSkuDetails(listOf(sku), emptyList(), PurchaseType.INAPP)

        capturedPurchasesUpdatedListener.captured.onPurchasesUpdated(getMockedPurchaseList(
            sku,
            purchaseToken,
            PurchaseType.UNKNOWN,
            "offering_a"
        ))
        verify (exactly = 1) {
            mockBillingWrapper.querySkuDetailsAsync(
                BillingClient.SkuType.INAPP,
                any(),
                any(),
                any()
            )
        }
    }

    private fun mockBackend(
        mockInfo: PurchaserInfo,
        errorGettingPurchaserInfo: PurchasesError? = null
    ) {
        with(mockBackend) {
            if (errorGettingPurchaserInfo != null) {
                every {
                    getPurchaserInfo(any(), any(), captureLambda())
                } answers {
                    lambda<(PurchasesError) -> Unit>().captured.invoke(errorGettingPurchaserInfo)
                }
            } else {
                every {
                    getPurchaserInfo(any(), captureLambda(), any())
                } answers {
                    lambda<(PurchaserInfo) -> Unit>().captured.invoke(mockInfo)
                }
            }
            mockProducts()
            every {
                postReceiptData(
                    purchaseToken = any(),
                    appUserID = any(),
                    isRestore = any(),
                    observerMode = any(),
                    subscriberAttributes = any(),
                    productInfo = any(),
                    onSuccess = captureLambda(),
                    onError = any()
                )
            } answers {
                lambda<PostReceiptDataSuccessCallback>().captured.invoke(mockInfo, emptyList())
            }
            every {
                close()
            } just Runs
        }
    }

    private fun mockCache(mockInfo: PurchaserInfo) {
        with(mockCache) {
            every {
                getCachedAppUserID()
            } returns null
            every {
                getCachedPurchaserInfo(any())
            } returns mockInfo
            every {
                cachePurchaserInfo(any(), any())
            } just Runs
            every {
                cacheAppUserID(any())
            } just Runs
            every {
                getCachedAttributionData(Purchases.AttributionNetwork.APPSFLYER, appUserId)
            } returns null
            every {
                setPurchaserInfoCacheTimestampToNow()
            } just Runs
            every {
                setOfferingsCacheTimestampToNow()
            } just Runs
            every {
                clearPurchaserInfoCacheTimestamp()
            } just Runs
            every {
                clearOfferingsCacheTimestamp()
            } just Runs
            every {
                isPurchaserInfoCacheStale()
            } returns false
            every {
                isOfferingsCacheStale()
            } returns false
            every {
                addSuccessfullyPostedToken(any())
            } just Runs
            every {
                mockCache.cacheOfferings(any())
            } just Runs
        }
    }

    private fun mockProducts() {
        every {
            mockBackend.getOfferings(any(), captureLambda(), any())
        } answers {
            lambda<(JSONObject) -> Unit>().captured.invoke(JSONObject(oneOfferingsResponse))
        }
    }

    private fun mockSkuDetails(
        skus: List<String>,
        skusSuccessfullyFetched: List<String>,
        type: PurchaseType
    ): List<SkuDetails> {
        val skuDetailsList = skusSuccessfullyFetched.map { sku ->
            mockk<SkuDetails>().also {
                every { it.sku } returns sku
            }
        }

        every {
            mockBillingWrapper.querySkuDetailsAsync(
                type.toSKUType()!!,
                skus,
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<SkuDetails>) -> Unit>().captured.invoke(skuDetailsList)
        }
        return skuDetailsList
    }

    private fun mockCloseActions() {
        every {
            ProcessLifecycleOwner.get()
        } returns mockLifecycleOwner
        every {
            mockLifecycleOwner.lifecycle
        } returns mockLifecycle
        every {
            mockLifecycle.removeObserver(any())
        } just Runs
        every {
            mockBackend.close()
        } just Runs
        every {
            mockBillingWrapper.purchasesUpdatedListener = null
        } just Runs
    }

    private fun mockAdInfo(limitAdTrackingEnabled: Boolean, networkUserID: String) {
        val adInfo = mockk<AdvertisingIdClient.AdInfo>()
        every { adInfo.isLimitAdTrackingEnabled } returns limitAdTrackingEnabled
        every { adInfo.id } returns if (!limitAdTrackingEnabled) adID else ""

        mockkObject(AdvertisingIdClient)

        val lst = slot<(AdvertisingIdClient.AdInfo?) -> Unit>()
        every {
            AdvertisingIdClient.getAdvertisingIdInfo(any(), capture(lst))
        } answers {
            lst.captured.invoke(adInfo)
        }

        every {
            mockCache.cacheAttributionData(
                Purchases.AttributionNetwork.APPSFLYER,
                appUserId,
                listOfNotNull(adID.takeUnless { limitAdTrackingEnabled }, networkUserID).joinToString("_")
            )
        } just Runs
    }

    private fun verifyClose() {
        verify {
            mockBackend.close()
        }
        assertThat(purchases.updatedPurchaserInfoListener).isNull()
        verify (exactly = 1) {
            mockLifecycle.removeObserver(any())
        }
        verifyOrder {
            mockBillingWrapper.purchasesUpdatedListener = capturedPurchasesUpdatedListener.captured
            mockBillingWrapper.purchasesUpdatedListener = null
        }
    }

    private fun getMockedPurchaseHistoryList(
        sku: String,
        purchaseToken: String,
        purchaseType: PurchaseType
    ): ArrayList<PurchaseHistoryRecordWrapper> {
        val p: PurchaseHistoryRecord = mockk()
        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken
        every {
            p.purchaseTime
        } returns System.currentTimeMillis()
        return ArrayList<PurchaseHistoryRecordWrapper>().also {
            it.add(PurchaseHistoryRecordWrapper(p, purchaseType))
        }
    }

    private fun getMockedPurchaseList(
        sku: String,
        purchaseToken: String,
        purchaseType: PurchaseType,
        offeringIdentifier: String? = null,
        purchaseState: Int = Purchase.PurchaseState.PURCHASED,
        acknowledged: Boolean = false
    ): ArrayList<PurchaseWrapper> {
        val p: Purchase = mockk()
        every {
            p.sku
        } returns sku
        every {
            p.purchaseToken
        } returns purchaseToken
        every {
            p.purchaseTime
        } returns System.currentTimeMillis()
        every {
            p.purchaseState
        } returns purchaseState
        every {
            p.isAcknowledged
        } returns acknowledged
        val purchasesList = ArrayList<PurchaseWrapper>()
        purchasesList.add(PurchaseWrapper(p, purchaseType, offeringIdentifier))
        return purchasesList
    }

    private fun mockSuccessfulQueryPurchases(
        queriedSUBS: Map<String, PurchaseWrapper>,
        queriedINAPP: Map<String, PurchaseWrapper>,
        notInCache: List<PurchaseWrapper>
    ) {
        every {
            mockExecutorService.isShutdown
        } returns false
        val queryPurchasesResultSUBS = BillingWrapper.QueryPurchasesResult(0, queriedSUBS)
        val queryPurchasesResultINAPP = BillingWrapper.QueryPurchasesResult(0, queriedINAPP)
        every {
            mockCache.cleanPreviouslySentTokens(
                queryPurchasesResultSUBS.purchasesByHashedToken.keys,
                queryPurchasesResultINAPP.purchasesByHashedToken.keys
            )
        } just Runs
        every {
            mockCache.getActivePurchasesNotInCache(
                queryPurchasesResultSUBS.purchasesByHashedToken,
                queryPurchasesResultINAPP.purchasesByHashedToken
            )
        } returns notInCache
        every {
            mockBillingWrapper.queryPurchases(PurchaseType.SUBS.toSKUType()!!)
        } returns queryPurchasesResultSUBS
        every {
            mockBillingWrapper.queryPurchases(PurchaseType.INAPP.toSKUType()!!)
        } returns queryPurchasesResultINAPP
    }

    private fun buildPurchases(anonymous: Boolean = false) {
        purchases = Purchases(
            mockApplication,
            if (anonymous) null else appUserId,
            mockBackend,
            mockBillingWrapper,
            mockCache,
            executorService = mockExecutorService,
            identityManager = mockIdentityManager,
            subscriberAttributesManager = mockSubscriberAttributesManager,
            appConfig = AppConfig(
                context = mockContext,
                observerMode = false,
                platformInfo = PlatformInfo("native", "3.2.0"),
                proxyURL = null
            )
        )
        Purchases.sharedInstance = purchases
    }

    private fun Int.buildResult(): BillingResult {
        return BillingResult.newBuilder().setResponseCode(this).build()
    }

    private fun mockPostReceiptError(
        sku: String,
        purchaseToken: String,
        observerMode: Boolean,
        offeringIdentifier: String?,
        type: PurchaseType,
        answer: MockKAnswerScope<Unit, Unit>.(Call) -> Unit
    ) {
        val productInfo = mockQueryingSkuDetails(sku, type, offeringIdentifier)

        every {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = false,
                observerMode = observerMode,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = any(),
                onError = captureLambda()
            )
        } answers answer
    }

    private fun mockPostReceipt(
        sku: String,
        purchaseToken: String,
        observerMode: Boolean,
        mockInfo: PurchaserInfo,
        offeringIdentifier: String?,
        type: PurchaseType
    ): ProductInfo {
        val productInfo = mockQueryingSkuDetails(sku, type, offeringIdentifier)

        every {
            mockBackend.postReceiptData(
                purchaseToken = purchaseToken,
                appUserID = appUserId,
                isRestore = false,
                observerMode = observerMode,
                subscriberAttributes = emptyMap(),
                productInfo = productInfo,
                onSuccess = captureLambda(),
                onError = any()
            )
        } answers {
            lambda<PostReceiptDataSuccessCallback>().captured.invoke(mockInfo, emptyList())
        }

        return productInfo
    }

    private fun mockQueryingSkuDetails(
        sku: String,
        type: PurchaseType,
        offeringIdentifier: String?
    ): ProductInfo {
        val skuDetails = mockk<SkuDetails>()
        every { skuDetails.priceAmountMicros } returns 2000000
        every { skuDetails.priceCurrencyCode } returns "USD"
        every { skuDetails.subscriptionPeriod } returns if (type == PurchaseType.SUBS) "P1M" else null
        every { skuDetails.introductoryPricePeriod } returns if (type == PurchaseType.SUBS) "P7D" else null
        every { skuDetails.freeTrialPeriod } returns if (type == PurchaseType.SUBS) "P7D" else null

        val productInfo = ProductInfo(
            productID = sku,
            offeringIdentifier = offeringIdentifier,
            skuDetails = skuDetails
        )

        val mockSkuDetails = mockk<SkuDetails>().also {
            every { it.sku } returns productInfo.productID
            every { it.priceAmountMicros } returns (productInfo.price!! * 1000000).toLong()
            every { it.priceCurrencyCode } returns productInfo.currency
            every { it.subscriptionPeriod } returns productInfo.duration
            every { it.introductoryPricePeriod } returns productInfo.introDuration
            every { it.freeTrialPeriod } returns productInfo.trialDuration
        }

        every {
            mockBillingWrapper.querySkuDetailsAsync(
                type.toSKUType()!!,
                listOf(sku),
                captureLambda(),
                any()
            )
        } answers {
            lambda<(List<SkuDetails>) -> Unit>().captured.invoke(listOf(mockSkuDetails))
        }

        return productInfo
    }

    private fun mockCacheStale(purchaserInfoStale: Boolean = false, offeringsStale: Boolean = false) {
        every {
            mockCache.isPurchaserInfoCacheStale()
        } returns purchaserInfoStale
        every {
            mockCache.isOfferingsCacheStale()
        } returns offeringsStale
    }

    private fun mockSubscriberAttributesManager(userIdToUse: String) {
        every {
            mockSubscriberAttributesManager.synchronizeSubscriberAttributesForAllUsers(appUserId)
        } just Runs
        every {
            mockSubscriberAttributesManager.getUnsyncedSubscriberAttributes(userIdToUse)
        } returns emptyMap()
        every {
            mockSubscriberAttributesManager.markAsSynced(userIdToUse, any(), any())
        } just runs
    }

    // endregion
}
