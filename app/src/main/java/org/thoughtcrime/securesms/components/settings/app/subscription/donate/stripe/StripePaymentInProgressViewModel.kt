package org.thoughtcrime.securesms.components.settings.app.subscription.donate.stripe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.wallet.PaymentData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.signal.core.util.logging.Log
import org.signal.donations.GooglePayPaymentSource
import org.signal.donations.StripeApi
import org.thoughtcrime.securesms.components.settings.app.subscription.DonationPaymentRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.DonateToSignalType
import org.thoughtcrime.securesms.components.settings.app.subscription.donate.gateway.GatewayRequest
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.DonationError
import org.thoughtcrime.securesms.components.settings.app.subscription.errors.DonationErrorSource
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.MultiDeviceSubscriptionSyncRequestJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.rx.RxStore
import org.whispersystems.signalservice.api.subscriptions.SubscriptionLevels
import org.whispersystems.signalservice.api.util.Preconditions

class StripePaymentInProgressViewModel(
  private val donationPaymentRepository: DonationPaymentRepository
) : ViewModel() {

  companion object {
    private val TAG = Log.tag(StripePaymentInProgressViewModel::class.java)
  }

  private val store = RxStore(StripeStage.INIT)
  val state: Flowable<StripeStage> = store.stateFlowable.observeOn(AndroidSchedulers.mainThread())

  private val disposables = CompositeDisposable()
  private var paymentData: PaymentData? = null
  private var cardData: StripeApi.CardData? = null

  override fun onCleared() {
    disposables.clear()
    store.dispose()
    clearPaymentInformation()
  }

  fun onBeginNewAction() {
    Preconditions.checkState(!store.state.isInProgress)

    Log.d(TAG, "Beginning a new action. Ensuring cleared state.", true)
    disposables.clear()
  }

  fun onEndAction() {
    Preconditions.checkState(store.state.isTerminal)

    Log.d(TAG, "Ending current state. Clearing state and setting stage to INIT", true)
    store.update { StripeStage.INIT }
    disposables.clear()
  }

  fun processNewDonation(request: GatewayRequest, nextActionHandler: (StripeApi.Secure3DSAction) -> Completable) {
    Log.d(TAG, "Proceeding with donation...", true)

    val errorSource = when (request.donateToSignalType) {
      DonateToSignalType.ONE_TIME -> DonationErrorSource.BOOST
      DonateToSignalType.MONTHLY -> DonationErrorSource.SUBSCRIPTION
    }

    val paymentSourceProvider: Single<StripeApi.PaymentSource> = resolvePaymentSourceProvider(errorSource)

    return when (request.donateToSignalType) {
      DonateToSignalType.MONTHLY -> proceedMonthly(request, paymentSourceProvider, nextActionHandler)
      DonateToSignalType.ONE_TIME -> proceedOneTime(request, paymentSourceProvider, nextActionHandler)
    }
  }

  private fun resolvePaymentSourceProvider(errorSource: DonationErrorSource): Single<StripeApi.PaymentSource> {
    val paymentData = this.paymentData
    val cardData = this.cardData

    return when {
      paymentData == null && cardData == null -> error("No payment provider available.")
      paymentData != null && cardData != null -> error("Too many providers available")
      paymentData != null -> Single.just<StripeApi.PaymentSource>(GooglePayPaymentSource(paymentData))
      cardData != null -> donationPaymentRepository.createCreditCardPaymentSource(errorSource, cardData)
      else -> error("This should never happen.")
    }.doAfterTerminate { clearPaymentInformation() }
  }

  fun providePaymentData(paymentData: PaymentData) {
    requireNoPaymentInformation()
    this.paymentData = paymentData
  }

  fun provideCardData(cardData: StripeApi.CardData) {
    requireNoPaymentInformation()
    this.cardData = cardData
  }

  private fun requireNoPaymentInformation() {
    require(paymentData == null)
    require(cardData == null)
  }

  private fun clearPaymentInformation() {
    Log.d(TAG, "Cleared payment information.", true)
    paymentData = null
    cardData = null
  }

  private fun proceedMonthly(request: GatewayRequest, paymentSourceProvider: Single<StripeApi.PaymentSource>, nextActionHandler: (StripeApi.Secure3DSAction) -> Completable) {
    val ensureSubscriberId: Completable = donationPaymentRepository.ensureSubscriberId()
    val createAndConfirmSetupIntent: Single<StripeApi.Secure3DSAction> = paymentSourceProvider.flatMap { donationPaymentRepository.createAndConfirmSetupIntent(it) }
    val setLevel: Completable = donationPaymentRepository.setSubscriptionLevel(request.level.toString())

    Log.d(TAG, "Starting subscription payment pipeline...", true)
    store.update { StripeStage.PAYMENT_PIPELINE }

    val setup: Completable = ensureSubscriberId
      .andThen(cancelActiveSubscriptionIfNecessary())
      .andThen(createAndConfirmSetupIntent)
      .flatMap { secure3DSAction -> nextActionHandler(secure3DSAction).andThen(Single.just(secure3DSAction.paymentMethodId!!)) }
      .flatMapCompletable { donationPaymentRepository.setDefaultPaymentMethod(it) }
      .onErrorResumeNext { Completable.error(DonationError.getPaymentSetupError(DonationErrorSource.SUBSCRIPTION, it)) }

    disposables += setup.andThen(setLevel).subscribeBy(
      onError = { throwable ->
        Log.w(TAG, "Failure in subscription payment pipeline...", throwable, true)
        store.update { StripeStage.FAILED }

        val donationError: DonationError = if (throwable is DonationError) {
          throwable
        } else {
          DonationError.genericBadgeRedemptionFailure(DonationErrorSource.SUBSCRIPTION)
        }
        DonationError.routeDonationError(ApplicationDependencies.getApplication(), donationError)
      },
      onComplete = {
        Log.d(TAG, "Finished subscription payment pipeline...", true)
        store.update { StripeStage.COMPLETE }
      }
    )
  }

  private fun cancelActiveSubscriptionIfNecessary(): Completable {
    return Single.just(SignalStore.donationsValues().shouldCancelSubscriptionBeforeNextSubscribeAttempt).flatMapCompletable {
      if (it) {
        Log.d(TAG, "Cancelling active subscription...", true)
        donationPaymentRepository.cancelActiveSubscription().doOnComplete {
          SignalStore.donationsValues().updateLocalStateForManualCancellation()
          MultiDeviceSubscriptionSyncRequestJob.enqueue()
        }
      } else {
        Completable.complete()
      }
    }
  }

  private fun proceedOneTime(
    request: GatewayRequest,
    paymentSourceProvider: Single<StripeApi.PaymentSource>,
    nextActionHandler: (StripeApi.Secure3DSAction) -> Completable
  ) {
    Log.w(TAG, "Beginning one-time payment pipeline...", true)

    val amount = request.fiat
    val recipient = Recipient.self().id
    val level = SubscriptionLevels.BOOST_LEVEL.toLong()

    val continuePayment: Single<StripeApi.PaymentIntent> = donationPaymentRepository.continuePayment(amount, recipient, level)
    val intentAndSource: Single<Pair<StripeApi.PaymentIntent, StripeApi.PaymentSource>> = Single.zip(continuePayment, paymentSourceProvider, ::Pair)

    disposables += intentAndSource.flatMapCompletable { (paymentIntent, paymentSource) ->
      donationPaymentRepository.confirmPayment(paymentSource, paymentIntent, recipient)
        .flatMapCompletable { nextActionHandler(it) }
        .andThen(donationPaymentRepository.waitForOneTimeRedemption(amount, paymentIntent, recipient, null, level))
    }.subscribeBy(
      onError = { throwable ->
        Log.w(TAG, "Failure in one-time payment pipeline...", throwable, true)
        store.update { StripeStage.FAILED }

        val donationError: DonationError = if (throwable is DonationError) {
          throwable
        } else {
          DonationError.genericBadgeRedemptionFailure(DonationErrorSource.BOOST)
        }
        DonationError.routeDonationError(ApplicationDependencies.getApplication(), donationError)
      },
      onComplete = {
        Log.w(TAG, "Completed one-time payment pipeline...", true)
        store.update { StripeStage.COMPLETE }
      }
    )
  }

  fun cancelSubscription() {
    Log.d(TAG, "Beginning cancellation...", true)

    store.update { StripeStage.CANCELLING }
    disposables += donationPaymentRepository.cancelActiveSubscription().subscribeBy(
      onComplete = {
        Log.d(TAG, "Cancellation succeeded", true)
        SignalStore.donationsValues().updateLocalStateForManualCancellation()
        MultiDeviceSubscriptionSyncRequestJob.enqueue()
        donationPaymentRepository.scheduleSyncForAccountRecordChange()
        store.update { StripeStage.COMPLETE }
      },
      onError = { throwable ->
        Log.w(TAG, "Cancellation failed", throwable, true)
        store.update { StripeStage.FAILED }
      }
    )
  }

  fun updateSubscription(request: GatewayRequest) {
    Log.d(TAG, "Beginning subscription update...", true)

    store.update { StripeStage.PAYMENT_PIPELINE }
    disposables += cancelActiveSubscriptionIfNecessary().andThen(donationPaymentRepository.setSubscriptionLevel(request.level.toString()))
      .subscribeBy(
        onComplete = {
          Log.w(TAG, "Completed subscription update", true)
          store.update { StripeStage.COMPLETE }
        },
        onError = { throwable ->
          Log.w(TAG, "Failed to update subscription", throwable, true)
          val donationError: DonationError = if (throwable is DonationError) {
            throwable
          } else {
            DonationError.genericBadgeRedemptionFailure(DonationErrorSource.SUBSCRIPTION)
          }
          DonationError.routeDonationError(ApplicationDependencies.getApplication(), donationError)

          store.update { StripeStage.FAILED }
        }
      )
  }

  class Factory(
    private val donationPaymentRepository: DonationPaymentRepository
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return modelClass.cast(StripePaymentInProgressViewModel(donationPaymentRepository)) as T
    }
  }
}
